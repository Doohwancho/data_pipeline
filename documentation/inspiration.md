---
하루 데이터 처리량 10억건 

https://www.youtube.com/watch?v=vmpcmhbUl-w&ab_channel=%EA%B0%9C%EB%B0%9C%EB%B0%94%EB%8B%A5

1일 1억건 로그 수용하는 로그 모니터링 

- 데이터 저장 서버 vCPU 4core, 16GiB RAM 
- 데이터 건수: 10억건 / 일 (약 70만건 / 분)
- disk 사용량: 약 95GiB / 일 (약 68MB / 분)
- 인덱스 기반 조회 속도: 1건/296ms, 63건/420ms, 14만건/1232ms 


주요기능에 대해 최대 메모리 사용량과 thread pool size직접 조절하여 성능 최적화 
jdk8, springboot 2.x, mysql8, jpa 2.x, tcp socket 자체개발, file database
ec2, rds, s3, cloud watch, lambda

---

걍 단순 로그 수집 -> 적재가 아니고, 중간에 format 변경하는 전처리 단계를 거쳤음

1. 솔루션은 최대 메모리 사용량과 thread를 수동으로 조절함
2. 1로그당 1객체 생성도 너무 크다. -> 생성비용이 큰 객체를 캐싱하여 1번만 객체생성되도록 변경
3. ec2에 로그 쌓아놓는것도 disk용량제한에 따라 원본데이터는 보통 보관기간 7일 이하로 짧은데, 통계성 데이터는 1년까지 조회할 수 있어야 함.
4. 모니터링결과 Files.getLastModifiedTime에서 stacktrace가 자주 발견된걸 확인 ->
   disk iops가 write 3k, read 1k로 시스템에 한계에 도달했음을 확인
   -> Files.Files.getLastModifiedTime이 불필요하게 호출되는거 제거
   -> scale-out으로 물리적인 서버 분리
   -> 분리된 서버를 통합해서 검색할 수 있는 기능 개발
