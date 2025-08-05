# A. what

## --- 1. 수신 및 스레드 할당 계층 (OS & WAS Layer) ---

### Step 1: 요청 수신 및 Worker 스레드 배정

1. 네트워크 인터페이스 카드(NIC) & OS 커널:
   - 클라이언트(브라우저, 앱 등)의 HTTP 요청은 TCP/IP 패킷 형태로 서버의 물리적 NIC에 도달합니다.
   - OS 커널의 네트워크 스택은 이 패킷을 재조립하여 TCP 소켓 스트림으로 만들고, WAS가 리스닝하는 포트(예: 8080)로 전달합니다.
2. WAS의 I/O 스레드와 Worker 스레드 풀:
   - 현대 WAS(Tomcat, Netty 기반의 Spring WebFlux 등)는 Non-blocking I/O(NIO) 모델을 사용합니다. 소수의 I/O 스레드(Selector 스레드)가 여러 클라이언트의 연결을 동시에 관리합니다.
   - I/O 스레드는 요청 데이터 읽기가 완료되면, 실제 비즈니스 로직을 처리할 Worker 스레드에게 작업을 위임합니다. 이 Worker 스레드는 WAS가 미리 생성해 둔 **스레드 풀(Thread Pool)**에서 가져옵니다.
   - 이 시점부터 요청 하나는 Worker 스레드 하나와 1:1로 매칭됩니다. 이 스레드는 JVM에 의해 관리되는 OS 네이티브 스레드이며, OS의 스케줄러에 의해 CPU Core에 할당되어 실행 시간을 얻습니다.

## --- 2. 애플리케이션 실행 계층 (JVM & Application Layer) ---

### Step 2: 비즈니스 로직 실행과 JVM 메모리 활용

1. 스택(Stack) 메모리 활용:

- 할당된 Worker 스레드는 자신만의 독립적인 스택(Stack) 메모리 영역을 가집니다.
- 요청이 컨트롤러(@Controller) -> 서비스(@Service) -> 리포지토리(@Repository) 순으로 호출될 때마다, 각 메소드 호출 정보(매개변수, 지역 변수, 반환 주소 등)가 스택 프레임(Stack Frame) 형태로 해당 스레드의 스택에 차곡차곡 쌓입니다.

2. 힙(Heap) 메모리 활용:
   - 메소드 내부에서 new UserDto()나 new ArrayList<>()처럼 객체를 생성하면, 이 객체들은 JVM의 모든 스레드가 공유하는 힙(Heap) 메모리 영역에 생성됩니다.
   - 스택에는 이 힙에 생성된 객체를 가리키는 참조(reference) 값만 저장됩니다.
3. JIT (Just-In-Time) 컴파일러:
   - 최초 실행 시, Java 바이트코드는 JVM의 인터프리터에 의해 한 줄씩 해석되며 실행됩니다.
   - 하지만 특정 서비스나 리포지토리의 메소드처럼 반복적으로 호출되어 "뜨거운(Hotspot)" 코드가 되면, JVM의 JIT 컴파일러가 작동합니다.
   - JIT 컴파일러는 이 바이트코드를 OS와 CPU에 최적화된 네이티브 머신 코드로 컴파일하여 캐싱합니다. 이후 동일한 코드 호출 시, 인터프리팅 없이 네이티브 코드가 직접 실행되어 성능이 대폭 향상됩니다.

### Step 3: 커넥션 풀(Connection Pool)에서 커넥션 획득

1. 커넥션 획득 요청:

- 리포지토리 계층(@Repository)의 코드가 DB 작업을 위해 dataSource.getConnection()을 호출합니다. 이는 미리 설정된 DB 커넥션 풀(주로 HikariCP)에 연결을 요청하는 것입니다.
- 왜 커넥션 풀을 사용하는가? TCP 연결 수립, MySQL 인증 및 세션 설정은 비용이 매우 높은 작업입니다. 매번 이 과정을 반복하지 않고, 미리 생성된 커넥션을 재사용하여 오버헤드를 최소화하고 응답 시간을 단축하기 위함입니다.

2. 커넥션 풀의 동작:
   - Worker 스레드는 커넥션 풀에 유휴(idle) 상태의 커넥션이 있는지 확인합니다.
   - 가용한 커넥션이 있을 경우: 즉시 커넥션을 받아 다음 단계로 진행합니다. 이 커넥션 객체는 사실 실제 물리적 커넥션을 감싼 프록시(Proxy) 객체입니다.
   - 가용한 커넥션이 없을 경우: Worker 스레드는 WAITING 또는 TIMED_WAITING 상태에 들어가 다른 스레드가 커넥션을 반납할 때까지 대기합니다. (이때 CPU 자원을 소모하지 않습니다.) 만약 설정된 타임아웃(예: connectionTimeout)을 초과하면 예외가 발생합니다.

## --- 3. JDBC 및 네트워크 전송 계층 (Driver & OS Layer) ---

### Step 4: JDBC API 호출 및 드라이버의 역할

1. PreparedStatement 생성 및 파라미터 바인딩:
   - 애플리케이션은 connection.prepareStatement("SELECT \* FROM users WHERE id = ?")와 같이 PreparedStatement를 생성합니다.
   - 성능 및 보안적 이점: Statement와 달리 PreparedStatement를 사용하면, SQL 템플릿(Query Plan)이 MySQL 서버에 의해 캐싱될 수 있습니다. ?에 들어가는 파라미터 값만 바꿔서 여러 번 실행하면, MySQL은 파싱과 최적화(MySQL 메모의 Step 3, 4) 단계를 건너뛰고 바로 실행 계획을 재사용할 수 있어 훨씬 효율적입니다. 또한, 파라미터가 데이터로 명확히 구분되어 전달되므로 SQL Injection 공격을 원천적으로 방어합니다.
   - ps.setLong(1, 12345L)처럼 파라미터를 설정하면, 이 값들은 JDBC 드라이버 내부에 임시로 저장됩니다.
2. 쿼리 실행 (executeQuery) 및 프로토콜 변환:
   - ps.executeQuery() 메소드가 호출되는 순간, MySQL Connector/J와 같은 JDBC 드라이버가 본격적으로 일을 시작합니다.
   - 드라이버는 PreparedStatement의 SQL 템플릿과 바인딩된 파라미터 값들을 조합하여 MySQL 클라이언트/서버 프로토콜 형식의 바이너리 데이터 패킷으로 직렬화(Serialization)합니다.

### Step 5: 소켓 쓰기(Socket Write)와 OS 커널을 통한 전송

1. 시스템 콜(System Call) 발생:
   - JDBC 드라이버는 커넥션 객체가 내부적으로 유지하고 있는 java.net.Socket 객체의 출력 스트림(OutputStream)에 이 바이너리 패킷을 씁니다.
   - 이 write() 작업은 JVM 레벨에서 끝나는 것이 아니라, OS 커널에 작업을 위임하는 **시스템 콜(System Call)**을 발생시킵니다. 이 순간, 스레드의 실행 모드는 사용자 모드(User Mode)에서 커널 모드(Kernel Mode)로 전환됩니다.
2. OS 네트워크 스택의 역할:
   - 커널 모드로 전환된 스레드를 대신해, OS 커널은 전달받은 데이터(MySQL 프로토콜 패킷)를 TCP/IP 스택을 통해 하위 계층으로 내려보냅니다.
   - TCP 계층: 데이터를 신뢰성 있게 전달하기 위해 세그먼트(Segment) 단위로 나누고, 출발지/목적지 포트 번호, 시퀀스 번호 등의 정보를 담은 TCP 헤더를 붙입니다.
   - IP 계층: 각 TCP 세그먼트에 출발지/목적지 IP 주소 정보를 담은 IP 헤더를 붙여 IP 패킷(Packet)을 만듭니다.
   - Data Link 계층: IP 패킷에 MAC 주소 등 물리적 주소 정보를 담은 이더넷 프레임(Frame) 헤더를 붙입니다.
3. 물리적 전송:
   - 최종적으로 만들어진 이더넷 프레임은 NIC(네트워크 인터페이스 카드)의 버퍼로 전송됩니다.
   - NIC는 이 프레임 데이터를 전기 신호 또는 빛 신호로 변환하여 물리적인 네트워크 케이블(LAN선, 광케이블)을 통해 MySQL 서버로 발사합니다.

# B 비동기 파이프라인 가이드라인

이 아키텍처는 '수집'과 '적재'를 완전히 분리한 비동기 파이프라인을 전제로 합니다.

## 1단계: 네트워크 계층 - 데이터 수신 (Network Layer)

- 상황: 수만 개의 클라이언트가 초당 수천 건의 로그 데이터를 동시에 전송합니다.
- 기술: Netty 기반의 자체 개발 TCP 소켓 서버. (e-2)

1. Connection 수립: Netty의 **Boss EventLoopGroup** 이 클라이언트의 TCP 연결 요청을 받습니다. 이 스레드는 연결 수립 외에는 아무 일도 하지 않고 즉시 다음 연결을 받으러 갑니다.
2. I/O 작업 위임: 수립된 연결(Channel)은 **Worker EventLoopGroup** 에 위임됩니다. Worker 스레드들은 Non-blocking I/O 방식으로 여러 채널로부터 데이터가 들어오는지 감시합니다.
3. 데이터 수신: 특정 채널에 데이터가 들어오면, Worker 스레드는 커널 버퍼에 있는 데이터를 애플리케이션의 **ByteBuf** 로 읽어들입니다. 여기까지는 순수 byte 데이터입니다.

최적화 포인트: HTTP/JSON이 아닌 경량 바이너리 프로토콜을 사용하여 네트워크 대역폭(e-1)과 CPU 파싱 비용을 최소화합니다.

## 2단계: 애플리케이션 계층 - 최소 처리 (Application Layer)

- 상황: Worker 스레드가 ByteBuf 형태의 원시 데이터를 받았습니다.
- 기술: Object Pooling 및 최소한의 데이터 처리 로직. (a-2)

1. 객체 재사용: new LogData()를 호출하는 대신, **미리 생성해 둔 객체 풀(Object Pool)** 에서 LogData 인스턴스를 하나 빌려옵니다. 이는 GC 발생을 억제하는 핵심적인 최적화입니다.
2. 데이터 파싱: ByteBuf를 파싱하여 재사용 객체의 필드를 채웁니다. 이 과정은 CPU를 소모하므로 최대한 가볍게 구현되어야 합니다.
3. 최소 검증: 데이터 포맷이 올바른지, 필수값이 누락되지 않았는지 등 최소한의 검증만 수행합니다. 복잡한 비즈니스 로직은 이 단계에서 절대 수행하지 않습니다.

## 3단계: 비동기 파이프라인 - 책임 전가 (Asynchronous Pipeline)

- 상황: 최소한의 처리가 끝난 LogData 객체가 준비되었습니다.
- 기술: 메시지 큐 (Kafka 등) 또는 메모리 기반의 Disruptor 같은 고성능 큐. (b-3, b-4)

1. 데이터 직렬화: LogData 객체를 다시 효율적인 바이너리 포맷(예: Protobuf, Avro)으로 직렬화합니다.
2. 큐에 적재: 직렬화된 데이터를 메시지 큐에 Producer로서 전송(Publish)합니다.
3. 객체 반납: 메시지 큐 전송이 완료되면, 2단계에서 빌렸던 LogData 객체를 다시 객체 풀에 반납하여 다음 요청 처리에 재사용되도록 합니다.

여기까지가 '데이터를 받는 쪽'의 역할입니다. 이 모든 과정은 밀리초(ms) 단위로 매우 빠르게 끝나며, 수집 서버는 DB의 상태와 관계없이 안정적으로 최대 성능을 낼 수 있습니다.

## 4단계: 배치 처리 계층 - 데이터 인출 및 그룹핑 (Batch Layer)

- 상황: 메시지 큐에는 처리되지 않은 데이터가 쌓여 있습니다. 별도의 서버에서 동작하는 **배치 애플리케이션(Consumer)** 이 이 데이터를 처리합니다.
- 기술: Spring Batch의 Partitioning, Multi-threaded Step 등. (b-2)

1. 데이터 인출: 컨슈머 스레드가 메시지 큐에서 정해진 개수(예: 10,000건)의 데이터를 한 번에 가져옵니다(Polling).
2. 데이터 역직렬화 및 그룹핑: 가져온 바이너리 데이터를 다시 LogData 객체로 변환하고(이때도 객체 풀 사용), `List<LogData>`와 같은 컬렉션에 담습니다. 이 리스트가 DB에 한 번에 적재될 하나의 '배치' 또는 '청크(Chunk)'가 됩니다.

## 5단계: JDBC 계층 - DB 적재 (JDBC Layer)

- 상황: 10,000건의 LogData 객체가 담긴 리스트가 준비되었습니다.
- 기술: rewriteBatchedStatements=true 옵션이 적용된 JDBC 배치 처리. (d-1)

1. 커넥션 획득: HikariCP와 같은 커넥션 풀에서 DB 커넥션을 하나 빌려옵니다.
2. 배치 준비: JdbcTemplate.batchUpdate()나 JpaRepository.saveAll() 같은 메서드가 호출됩니다. 내부적으로는 루프를 돌며 리스트에 있는 10,000개의 LogData를 PreparedStatement의 배치(batch)에 추가합니다 (addBatch()).
3. 쿼리 전송: executeBatch()가 호출되면, JDBC 드라이버는 rewriteBatchedStatements=true 설정에 따라 10,000개의 INSERT 문을 하나의 거대한 Multi-value INSERT 문으로 재작성합니다.
4. DB 실행: 재작성된 단 하나의 SQL 문이 네트워크를 통해 MySQL로 전송되고 실행됩니다.
5. 커밋 및 커넥션 반납: 트랜잭션이 커밋되고, 사용이 끝난 DB 커넥션은 다시 커넥션 풀로 반납됩니다.

# C. 최적화 기법

## a. --- java layer ---

### a-1. out of memory 피하기1 - 데이터 스트리밍 (Streaming Reads)

3천만 건의 데이터를 애플리케이션 메모리에 한 번에 올리는 것은 OutOfMemoryError를 유발합니다.

DB나 파일에서 원본 데이터를 읽을 때, 모든 결과를 메모리에 올리는 대신 데이터를 물 흐르듯이 한 건씩(row-by-row) 처리해야 합니다.

- Spring Batch: JdbcCursorItemReader를 사용하면 내부적으로 statement.setFetchSize(Integer.MIN_VALUE)를 설정하여 MySQL에서 스트리밍 방식으로 데이터를 가져옵니다.
- 파일 처리: 대용량 CSV나 JSON 파일을 읽을 때는 전체를 파싱하는 라이브러리 대신, 라인별/이벤트별로 처리하는 스트리밍 방식의 파서를 사용해야 합니다.

### a-2. OOM 피하기2: 1 row = 1 object creation 피하고, 1개 객체 돌려쓰기

"1로그당 1객체 생성도 너무 크다"는 말은 **가비지 컬렉터(Garbage Collector, GC)**의 부담을 의미합니다. 분당 70만 건의 로그가 들어올 때마다 객체를 생성하면, 메모리에 작은 객체들이 계속 쌓이고(Minor GC), 이들이 오래 살아남아 Old 영역으로 넘어가면 결국 시스템 전체를 멈추는 **'Stop-the-World' (Major GC)**를 유발합니다.

구현 방식: 로그 파싱에 필요한 객체나 데이터 전송용 객체(DTO)를 미리 정해진 개수만큼 만들어 둔 **객체 풀(Object Pool)**을 사용했을 것입니다. (예: Apache Commons Pool). 필요할 때마다 new로 생성하는 대신 풀에서 빌려 쓰고, 사용이 끝나면 반납하여 객체 생성을 최소화하고 GC 발생을 억제합니다.

## b. --- thread layer ---

### b-1. 메모리 && thread pool 수 직접 조절

단순히 application.yml을 수정하는 수준이 아닙니다.

JVM의 Heap 메모리(-Xmx), GC 알고리즘(G1GC, ZGC 등) 설정, 그리고 ThreadPoolTaskExecutor 같은 Spring의 스레드 풀을 프로파일링 결과에 기반하여 최적의 값으로 튜닝했을 것입니다.

로그 수집(I/O-bound) 작업과 전처리(CPU-bound) 작업의 특성에 맞춰 스레드 풀을 분리하고, 코어 수와 작업 큐 크기를 정밀하게 조절했을 가능성이 큽니다.

### b-2. parallel processing

1. Multi-threaded Step: 하나의 배치 작업을 여러 스레드가 나누어 병렬로 처리합니다. 3천만 건을 4개의 스레드가 약 750만 건씩 동시에 처리하여 전체 시간을 단축시킵니다.
2. Partitioning: 여기서 한 단계 더 나아가, 작업을 여러 개의 독립적인 '파티션'으로 분할하고, 각 파티션을 별도의 스레드나 심지어 별도의 서버(Worker)에서 실행합니다. 이는 애플리케이션 레벨에서 수평적으로 확장하는 가장 강력한 방법입니다.

### b-3. 비동기 파이프라인 구축

사용한 이유: 로그 수집, 전처리, DB 적재를 하나의 흐름으로 묶으면, 가장 느린 DB 적재 과정이 전체 시스템의 발목을 잡습니다.

구현 방식:

1. 수집 계층: 자체 개발한 TCP 서버는 데이터를 받아 메시지 큐(Kafka 등)나 자체 제작한 파일 DB에 던져 넣고 즉시 응답합니다.
2. 처리/적재 계층: 별도의 배치(Batch) 애플리케이션이 메시지 큐나 파일 DB에서 데이터를 안전하게 가져와, 포맷을 변경하고 MySQL에 Bulk Insert 합니다.

이 구조는 각 계층을 독립적으로 확장하고, DB 장애가 발생해도 로그 수집에는 영향이 없도록 만드는 핵심적인 설계입니다.

### b-4. 데이터 받는 쪽 thread갯수 미리 할당하고 디비 쓰는 쪽 thread 갯수 미리 할당하기

데이터를 받는 부분과 DB에 쓰는 부분을 완전히 분리하는 아키텍처입니다.

1. 수집(Producer): 외부로부터 들어오는 3천만 건의 데이터는 복잡한 처리 없이 즉시 Kafka, RabbitMQ 같은 메시지 큐에 던져 넣기만 합니다. 이 작업은 매우 빠릅니다.
2. 적재(Consumer): 별도의 컨슈머 애플리케이션(들)이 메시지 큐에서 데이터를 원하는 만큼 가져와, 위에서 설명한 JDBC 최적화, 배치 처리를 통해 DB에 여유롭게 적재합니다.

이 방식은 DB에 부하가 발생하더라도 데이터 수집 시스템은 영향을 받지 않고, 컨슈머의 개수를 늘려 쓰기 처리량을 유연하게 조절할 수 있는 매우 탄력적이고 안정적인 구조입니다.

## c. --- file i/o layer ---

### c-1. Bulk Insert를 위한 직접적인 접근 (LOAD DATA)

ORM의 편리함을 포기하고 성능을 극한으로 끌어올리는 방법입니다.

애플리케이션이 3천만 건의 데이터를 DB에 직접 INSERT 하는 대신, 빠른 로컬 디스크에 CSV 파일로 먼저 씁니다.

그 후, MySQL에 LOAD DATA LOCAL INFILE 명령어를 실행합니다.

이 명령어는 MySQL 클라이언트가 직접 CSV 파일을 읽어 서버로 전송하고, 서버는 SQL 처리 계층의 많은 부분을 건너뛰고 스토리지 엔진에 바로 데이터를 쓰기 때문에, JDBC를 통한 INSERT와는 비교할 수 없을 정도로 빠릅니다.

### c-2. sequantial file i/o instead of random access file i/o

파일 데이터베이스: 수신한 로그를 DB에 바로 INSERT하는 것은 디스크 I/O 병목을 유발합니다.

대신, 수신한 데이터를 **빠른 로컬 디스크에 순차적으로(sequentially) 기록하는 로그 파일(Write-Ahead Log, WAL과 유사한 형태)** 을 자체 제작했을 가능성이 높습니다.
디스크에 순차적으로 쓰는 작업은 랜덤하게 쓰는 것보다 훨씬 빠릅니다.

## d. --- jdbc layer ---

### d-1. batch로 1000개씩 묶어서 보내기

`rewriteBatchedStatements=true`
`url: jdbc:mysql://localhost:3306/mydatabase?rewriteBatchedStatements=true`

```
-- BEFORE (1000번의 네트워크 통신)
INSERT INTO sales (...) VALUES (...);
INSERT INTO sales (...) VALUES (...);
... (x1000)

-- AFTER (단 1번의 네트워크 통신)
INSERT INTO sales (...) VALUES (...), (...), ..., (...);
```

## e. --- network layer ---

### e-1. 대역폭 충분히 넓어야 한다.

### e-2. TCP 소켓 자체개발

일반적인 Spring MVC의 HTTP + JSON 방식은 대용량 로그 수신에 너무 무겁습니다. HTTP 프로토콜 파싱, JSON 직렬화/역직렬화 과정에서 발생하는 CPU 및 메모리 오버헤드를 감당할 수 없기 때문입니다.

TCP 소켓: Netty 같은 저수준 네트워크 프레임워크를 사용하거나 직접 구현하여, 정해진 프로토콜에 따라 최소한의 데이터(주로 byte[])만 주고받는 경량화된 수집 서버를 만들었을 것입니다. 이는 CPU 사용량을 극적으로 줄입니다.
