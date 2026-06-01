package com.ecom.product.search;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.json.JsonData;
import com.ecom.product.search.dto.FacetBucket;
import com.ecom.product.search.dto.ProductSearchResult;
import com.ecom.product.search.dto.SearchHitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregation;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightFieldParameters;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 22 — full-text search trên ES index {@code products}. Thay LIKE / GIN
 * trigram (Day 16) khi cần relevance ranking + typo tolerance + faceted count.
 *
 * <p><b>Query shape</b> (build bằng ES Java client {@code co.elastic.clients}):
 * <pre>
 * bool {
 *   must:   multi_match(q, fields=[name^3, description, brand], fuzziness=AUTO)
 *   filter: term(status=ACTIVE)              // luôn có — không search product nháp
 *   filter: term(categoryId)   nếu có
 *   filter: term(brand)        nếu có
 *   filter: range(price gte/lte) nếu có
 * }
 * aggs: terms(brand), terms(categoryId)      // facet count
 * highlight: name, description               // bôi vàng match
 * </pre>
 *
 * <p><b>Tại sao {@code name^3}?</b> Boost — match ở tên sản phẩm "đáng giá"
 * gấp 3 lần match ở description. Gõ "iphone" thì product TÊN iPhone xếp trên
 * product chỉ NHẮC iPhone trong mô tả. Đây là relevance tuning — thứ Postgres
 * LIKE không có (mọi match bằng nhau).
 *
 * <p><b>fuzziness AUTO</b>: ES cho phép sai 1-2 ký tự tùy độ dài từ
 * (Levenshtein distance). "iphon" → match "iphone". GIN trigram cũng fuzzy
 * phần nào nhưng không kiểm soát được edit distance + không rank theo độ gần.
 *
 * <p><b>{@code must} vs {@code filter}</b>: query text vào {@code must} (tính
 * score, ảnh hưởng ranking); category/brand/price/status vào {@code filter}
 * (KHÔNG tính score, được ES cache bitset → nhanh hơn + đúng ngữ nghĩa "lọc
 * cứng"). Junior hay nhét hết vào must → filter mất cache + ranking nhiễu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private static final String FACET_BRAND = "by_brand";
    private static final String FACET_CATEGORY = "by_category";
    private static final int FACET_SIZE = 20;

    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * @param q          từ khóa full-text; blank = match_all (browse mode)
     * @param categoryId filter cứng theo category (nullable)
     * @param brand      filter cứng theo brand (nullable)
     * @param priceMin   filter range giá tối thiểu (nullable)
     * @param priceMax   filter range giá tối đa (nullable)
     */
    public ProductSearchResult search(String q,
                                      String categoryId,
                                      String brand,
                                      BigDecimal priceMin,
                                      BigDecimal priceMax,
                                      int page,
                                      int size) {
        int safeSize = Math.min(Math.max(1, size), 100);
        int safePage = Math.max(0, page);
        String keyword = (q == null || q.isBlank()) ? null : q.trim();

        Query query = buildBoolQuery(keyword, categoryId, brand, priceMin, priceMax);

        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(query)
                .withPageable(PageRequest.of(safePage, safeSize))
                .withAggregation(FACET_BRAND, termsAgg("brand"))
                .withAggregation(FACET_CATEGORY, termsAgg("categoryId"))
                .withHighlightQuery(buildHighlight())
                .build();

        SearchHits<ProductDocument> hits =
                elasticsearchOperations.search(nativeQuery, ProductDocument.class);

        List<SearchHitResponse> items = hits.getSearchHits().stream()
                .map(ProductSearchService::toHitResponse)
                .toList();

        Map<String, List<FacetBucket>> facets = extractFacets(hits);

        return new ProductSearchResult(
                items,
                hits.getTotalHits(),
                safePage,
                safeSize,
                facets,
                "elasticsearch");
    }

    // ---- query builders -------------------------------------------------

    private Query buildBoolQuery(String keyword,
                                 String categoryId,
                                 String brand,
                                 BigDecimal priceMin,
                                 BigDecimal priceMax) {
        return Query.of(qb -> qb.bool(b -> {
            // must: full-text. Blank keyword → match_all (browse facet không cần từ khóa).
            if (keyword == null) {
                b.must(m -> m.matchAll(ma -> ma));
            } else {
                b.must(m -> m.multiMatch(mm -> mm
                        .query(keyword)
                        .type(TextQueryType.BestFields)
                        .fields("name^3", "description", "brand^2")
                        .fuzziness("AUTO")));
            }
            // filter cứng: status luôn ACTIVE — search KHÔNG trả DRAFT/ARCHIVED.
            b.filter(f -> f.term(t -> t.field("status").value("ACTIVE")));
            if (categoryId != null && !categoryId.isBlank()) {
                b.filter(f -> f.term(t -> t.field("categoryId").value(categoryId)));
            }
            if (brand != null && !brand.isBlank()) {
                b.filter(f -> f.term(t -> t.field("brand").value(brand)));
            }
            if (priceMin != null || priceMax != null) {
                // ES 8.15 RangeQuery là tagged union — số phải qua .untyped(...).
                b.filter(f -> f.range(r -> r.untyped(u -> {
                    u.field("price");
                    if (priceMin != null) u.gte(JsonData.of(priceMin.doubleValue()));
                    if (priceMax != null) u.lte(JsonData.of(priceMax.doubleValue()));
                    return u;
                })));
            }
            return b;
        }));
    }

    private Aggregation termsAgg(String field) {
        return Aggregation.of(a -> a.terms(t -> t.field(field).size(FACET_SIZE)));
    }

    private HighlightQuery buildHighlight() {
        HighlightFieldParameters params = HighlightFieldParameters.builder()
                .withPreTags("<em>")
                .withPostTags("</em>")
                .withNumberOfFragments(2)
                .withFragmentSize(150)
                .build();
        List<HighlightField> fields = List.of(
                new HighlightField("name", params),
                new HighlightField("description", params));
        Highlight highlight = new Highlight(HighlightParameters.builder().build(), fields);
        return new HighlightQuery(highlight, ProductDocument.class);
    }

    // ---- result mappers -------------------------------------------------

    private static SearchHitResponse toHitResponse(SearchHit<ProductDocument> hit) {
        ProductDocument doc = hit.getContent();
        List<String> highlights = new ArrayList<>();
        hit.getHighlightFields().values().forEach(highlights::addAll);
        return new SearchHitResponse(
                doc.getId(),
                doc.getSku(),
                doc.getName(),
                doc.getBrand(),
                doc.getPrice(),
                doc.getCurrency(),
                doc.getCategorySlug(),
                hit.getScore(),
                highlights);
    }

    private Map<String, List<FacetBucket>> extractFacets(SearchHits<ProductDocument> hits) {
        Map<String, List<FacetBucket>> facets = new LinkedHashMap<>();
        if (!(hits.getAggregations() instanceof ElasticsearchAggregations aggs)) {
            return facets;
        }
        facets.put("brand", buckets(aggs, FACET_BRAND));
        facets.put("category", buckets(aggs, FACET_CATEGORY));
        return facets;
    }

    private List<FacetBucket> buckets(ElasticsearchAggregations aggs, String name) {
        ElasticsearchAggregation agg = aggs.aggregationsAsMap().get(name);
        if (agg == null) {
            return List.of();
        }
        Aggregate aggregate = agg.aggregation().getAggregate();
        if (!aggregate.isSterms()) {
            return List.of();
        }
        List<FacetBucket> result = new ArrayList<>();
        for (StringTermsBucket bucket : aggregate.sterms().buckets().array()) {
            result.add(new FacetBucket(bucket.key().stringValue(), bucket.docCount()));
        }
        return result;
    }
}
