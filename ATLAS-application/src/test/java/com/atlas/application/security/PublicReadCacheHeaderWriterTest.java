package com.atlas.application.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 공개 읽기 응답에만 캐시 정책이 붙는지 고정합니다.
 *
 * <p>이 판정이 틀리면 두 방향으로 위험합니다. 공개 경로를 놓치면 캐시 이득이 사라지고,
 * 사용자별 경로를 잘못 포함하면 개인 데이터가 공유 캐시에 남습니다.
 */
class PublicReadCacheHeaderWriterTest {

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    @Test
    void publicCatalogReadsAreCacheable() {
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(get("/api/products"))).isTrue();
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/products/6f1c0b52-0000-7000-8000-000000000001"))).isTrue();
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/products/6f1c0b52-0000-7000-8000-000000000001/reviews"))).isTrue();
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/campaigns/6f1c0b52-0000-7000-8000-000000000001"))).isTrue();
    }

    @Test
    void userScopedReadsAreNotCacheable() {
        // 구매자·판매자·주문·인증은 사용자별 데이터이므로 공유 캐시에 남으면 안 된다.
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/auth/sessions"))).isFalse();
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/buyers/buyer-1/cart"))).isFalse();
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/sellers/6f1c0b52-0000-7000-8000-000000000001/products"))).isFalse();
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/checkouts/6f1c0b52-0000-7000-8000-000000000001"))).isFalse();
    }

    @Test
    void writesAreNeverCached() {
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/products");
        post.setRequestURI("/api/products");
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(post)).isFalse();
    }

    @Test
    void nestedTrailingSegmentsAreNotCacheable() {
        // 목록 하위의 더 깊은 경로는 이 정책 대상이 아니다(오탐 방지).
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(
                get("/api/products/a/reviews/b"))).isFalse();
    }
    @Test
    void publicResponseGetsSharedCachePolicy() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PublicReadCacheHeaderWriter().writeHeaders(get("/api/products"), response);

        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("public, max-age=0, s-maxage=1");
        assertThat(response.getHeader("Pragma")).isNull();
        assertThat(response.getHeaders("Vary")).contains("Authorization", "Accept-Encoding", "Cookie");
    }

    @Test
    void userScopedResponseExplicitlyRemainsNoStore() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PublicReadCacheHeaderWriter().writeHeaders(get("/api/auth/sessions"), response);

        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    @Test
    void authorizedRequestToPublicPathIsNotSharedCacheable() {
        MockHttpServletRequest request = get("/api/products");
        request.addHeader("Authorization", "Bearer token");
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(request)).isFalse();
    }

    @Test
    void errorsOnPublicPathsAreNeverSharedCacheable() {
        MockHttpServletRequest request = get("/api/products/missing");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        new PublicReadCacheHeaderWriter().writeHeaders(request, response);

        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
    }

    @Test
    void cookieBearingRequestsAreNotPublicCacheable() {
        MockHttpServletRequest request = get("/api/products");
        request.addHeader("Cookie", "future-session=opaque");
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(request)).isFalse();
    }

    @Test
    void onlyKnownQueryParametersAreCacheable() {
        MockHttpServletRequest known = get("/api/products");
        known.setParameter("limit", "100");
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(known)).isTrue();

        MockHttpServletRequest unknown = get("/api/products");
        unknown.setParameter("cacheProbe", "random-value");
        assertThat(PublicReadCacheHeaderWriter.isPublicRead(unknown)).isFalse();
    }

}
