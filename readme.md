# A. 요구사항 
1. 이커머스에서 실시간 유저 행동 데이터 수집 
2. 그 외에 나머지 데이터는 추후 머신러닝 학습을 위해 data-lake에 저장한다. 


# B. 아키텍처 
![](./documentation/pipeline_architecture.png)

1. react에서 바로 kafka로 유저 행동 데이터를 보낼 수 없기 때문에, 비동기로 데이터 파이프라인 서버인 go(or java, etc)로 보냄 
    1. aws에서는 api gateway로 교체 
2. go에서 받은 유저 행동 데이터를 kafka에 넣음 
3. flink (실시간 스트림 데이터 ETL)
    1. kafka에서 flink로 데이터가 가서 flink가 실시간 데이터를 전처리 하는데, 지금 보면 딱히 전처리를 할게 있나 싶긴 해. 아 근데 저 정보를 다 쓰지는 않고, 여기서 redis를 호출해서, 유저 id : list(최근에 interact한 product_id 순 정렬 ASC)
    2. flink 쓰는이유가 'stateful stream processing'이라서인데... 활용 잘 못하는 느낌 
    3. 추천 모델에 맞는 '실시간 피처 엔지니어링'을 flink한테 유저별 '세션'별로 하는 느낌? 
    4. Flink의 가장 기본적인 상태 처리 기능은 **시간 기반 윈도우(Time-based Window)** 를 사용하여 유저의 단기적인 선호도를 집계하는 것
    5. examples 
        1. **평균 조회 상품 가격:** 유저가 최근 10분간 조회(`event_type: 'view'`)한 상품들의 평균 `price`를 계산합니다. 이 유저가 지금 고가 상품에 관심이 있는지, 저가 상품을 둘러보는지 알 수 있습니다. 
        2. **최다 인터랙션 카테고리:** 외부 DB와 연동하여 `product_id`에 해당하는 카테고리 정보를 가져온 뒤, 최근 30분간 가장 많이 상호작용한 `category`를 파악합니다. 
        3. '장바구니 이탈' 패턴 감지: 
            1. 상품을 `add_to_cart` 한다.
            2. **하지만** 10분 이내에 `purchase` 이벤트가 발생하지 않는다.
            3. → 이 패턴이 감지되면 "장바구니 이탈 임박" 유저로 분류하고 리타겟팅 메시지나 쿠폰을 즉시 발송할 수 있습니다.
        4. **'상품 비교' 패턴 감지:**
            1. 동일 카테고리의 상품을 3개 이상 연속으로 조회한다 (`view`).
            2. 그중 2개 이상을 `add_to_cart` 한다.
            3. → "상품 비교중인" 유저로 판단하고, 두 상품의 비교 정보나 할인 정보를 제공하여 구매를 유도할 수 있습니다.
        5. 결과 `user_features:{user_id}` 해시에 상태 플래그(flag)를 추가합니다. 
            1. `Field 4`: `pattern_detected`, `Value 4`: `"cart_abandonment_risk"`
4. 추천 서버(fastapi)에서는, redis에서 유저별 최신 product_id list 50개 element 있는거 받아와서 추천 모델에 넣고 서빙함. 
5. 배치성 데이터 파이프라인 
    1. 그리고 배치성 데이터 파이프라인으로 kafka에서 같은 유저 행동 데이터 구독해와서, s3에 배치로 적재함. 
    2. ram에 일정부분 쌓고, 압축파일로 만들어서 s3에 적재 (s3에 최적화된 파일타입 쓰기)


#  C. 데이터의 구조 
실시간 유저 행동 데이터 
```yaml
{
  // --- 기본 정보: "누가, 무엇을, 언제" ---
  "event_id": "evt_1752320520123_abcdefg",  // 이벤트 고유 ID: 모든 개별 행동을 식별하는 세상에 단 하나뿐인 값입니다. 데이터 정합성과 추적에 필수적입니다.
  "user_id": "00000dbacae5abe5e23885899a1fa44253a17956c6d1c3d25f88aa139fdfc657",               // 유저 ID: 행동의 주체인 유저를 식별합니다. 개인화 추천의 가장 핵심적인 키(Key)입니다.
  "session_id": "session_xyz_789",         // 세션 ID: 유저가 웹사이트에 방문해서 떠날 때까지의 활동 단위를 식별합니다. 유저의 한 번의 방문 여정을 분석하는 데 사용됩니다.
  "event_type": "add_to_cart",             // 이벤트 종류: 유저가 수행한 행동의 유형입니다. '클릭', '구매' 등과 함께 추천 모델에게 가장 강력한 신호 중 하나입니다.
  "timestamp": 1752320520123,              // 타임스탬프: 이벤트가 발생한 정확한 시간(밀리초)입니다. 유저 행동의 순서를 파악하는 데 결정적인 역할을 합니다.

  // --- 컨텍스트 정보: "어떤 상황에서" ---
  "context": {
    // 페이지 컨텍스트
    "page": {
      "url": "/products/prod_123",         // 페이지 URL: 이벤트가 발생한 구체적인 페이지 주소입니다.
      "referrer": "instagram.com"          // 유입 경로: 유저가 이 페이지에 도달하기 직전에 있었던 외부 소스입니다. (예: google.com, instagram.com, direct)
    },
    // 디바이스 컨텍스트
    "device": {
      "type": "mobile"                     // 디바이스 종류: 유저가 사용한 기기(mobile, desktop)입니다. 기기별 행동 패턴을 분석하는 데 사용됩니다.
    },
    // 날씨 컨텍스트
    "weather": {
      "status": "sunny",                   // 날씨 상태: 유저 위치의 날씨입니다. (예: sunny, rainy, cloudy) 패션, 여행 상품 추천에 큰 영향을 줍니다.
      "temperature": 26.5                  // 온도: 유저 위치의 기온입니다.
    },
    // 시간 컨텍스트
    "temporal": {
      "time_of_day": "afternoon",          // 시간대: 이벤트가 발생한 시간대입니다. (예: morning, afternoon, evening, night)
      "day_of_week": "saturday"            // 요일: 이벤트가 발생한 요일입니다. (예: weekday, weekend) 주중/주말 구매 패턴 분석에 유용합니다.
    },
    // 유저 상태 컨텍스트
    "user": {
      "login_status": true,                // 로그인 상태: 유저의 로그인 여부입니다. 회원/비회원 행동을 구분합니다.
      "segment": "returning_visitor"       // 유저 세그먼트: 유저의 그룹입니다. (예: new_visitor, returning_visitor, vip) 그룹별 맞춤 전략에 사용됩니다.
    },
    // 마케팅 캠페인 컨텍스트
    "campaign": {
      "utm_source": "instagram",           // UTM 소스: 어떤 마케팅 채널을 통해 유입되었는지 나타냅니다. (예: google, facebook, newsletter)
      "utm_campaign": "summer_linen_special" // UTM 캠페인: 특정 마케팅 캠페인의 이름입니다. 캠페인별 성과 분석 및 추천 최적화에 사용됩니다.
    }
  },

  // --- 속성 정보: "무엇에 대해" ---
  "properties": {
    "product_id": "prod_123",              // 상품 ID: 이벤트의 대상이 된 상품의 고유 식별자입니다.
    "price": 45000                         // 가격: 이벤트 발생 시점의 상품 가격입니다. 가격대별 선호도를 분석하는 데 사용될 수 있습니다.
  }
}
```
