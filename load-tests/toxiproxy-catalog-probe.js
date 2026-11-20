import http from 'k6/http';

// PostgreSQL 장애가 독립적인 상품 조회에 미치는 영향. 결제 경로는 포함하지 않는다.
// 부하는 개방형 도착률로 유지하며 응답 상태와 dropped iterations를 함께 확인한다.
export const options = {
  scenarios: {
    catalog: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20),
      timeUnit: '1s',
      duration: __ENV.DURATION || '10s',
      preAllocatedVUs: Number(__ENV.VUS || 40),
      maxVUs: Number(__ENV.VUS || 40),
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)'],
};

const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:18980').replace(/\/$/, '');

export default function () {
  http.get(`${baseUrl}/api/products?limit=1`, {
    timeout: '5s',
    tags: { name: 'products' },
  });
}
