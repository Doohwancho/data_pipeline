# A. How to run?

```bash
cd terraform/ec2_rds_monitoring

terraform init

# window 기준, 관리자 모드로 파워쉘 시작 -> Chocolatey로 설치 (권장)
choco install terraform

# AWS CLI 설치 (이미 설치되어 있다면 생략)
choco install awscli

# AWS 자격 증명 설정 (iam에서 유저에서 특정 유저에 policy 적용하고 만들면, access key & secret key받는게 그거 등록)
aws configure
# AWS Access Key ID 입력
# AWS Secret Access Key 입력
# Default region: ap-northeast-2
# Default output format: json

# 3. 계획 확인 (선택사항)
terraform plan

# 4. 인프라 생성
terraform apply

# 모든 리소스 삭제
terraform destroy
```

# B. Terraform 인프라 실행 시 생성되는 AWS 인스턴스들

이 Terraform 코드를 실행하면 **다음과 같은 AWS 인스턴스 및 리소스**가 생성됩니다.

---

## 1. EC2 웹서버 인스턴스 (`aws_instance.webserver`)

- **인스턴스 타입:** `t4g.medium` (ARM64 아키텍처, 2코어, 4GB RAM)
- **AMI:** `ami-030be76ca6d557a` (Packer로 생성한 커스텀 AMI)
- **위치:** Public Subnet (첫 번째 AZ)
- **용도:** 웹 애플리케이션 서버

**특징**

- Elastic IP 할당
- `user_data`를 통해 RDS 연결 정보 자동 주입
- IAM 인스턴스 프로파일 적용
- **보안 그룹:** `websvr_sg` (포트 80, 443, 8080, 9090 허용)

---

## 2. Prometheus 모니터링 인스턴스 (`aws_instance.prometheus_instance`)

- **인스턴스 타입:** `t3a.medium` (AMD64 아키텍처, 2코어, 4GB RAM)
- **AMI:** `ami-0419dc605b6dde61f` (Ubuntu 18.04)
- **위치:** Public Subnet (첫 번째 AZ)
- **용도:** Prometheus + Grafana 모니터링 서버

**특징**

- Elastic IP 할당
- PMM(Percona Monitoring and Management) 서버 실행
- 포트 3000(Grafana), 8080(PMM) 접근 가능
- **보안 그룹:** `prometheus_sg`

---

## 3. RDS MySQL 데이터베이스 인스턴스 (`aws_db_instance.database`)

- **인스턴스 타입:** `db.t4g.medium` (2 vCPU, 4GB RAM)
- **엔진:** MySQL 8.0
- **위치:** Database Subnet Group (Private Subnets)
- **용도:** 애플리케이션 데이터베이스

**특징**

- Enhanced Monitoring 활성화(60초 간격)
- Performance Schema 활성화
- **보안 그룹:** `db_sg` (포트 3306만 허용)
- 웹서버/Prometheus 서버에서만 접근 허용

---

## 4. 네트워킹 인프라

- **VPC:** 10.0.0.0/16 CIDR
- **Public Subnets:** 3개 AZ (10.0.101.0/24, 10.0.102.0/24, 10.0.103.0/24)
- **Private Subnets:** 3개 AZ (10.0.1.0/24, 10.0.2.0/24, 10.0.3.0/24)
- **Database Subnets:** 3개 AZ (10.0.21.0/24, 10.0.22.0/24, 10.0.23.0/24)
- **NAT Gateway:** 1개 (Private Subnet의 인터넷 접근용)

---

## 5. IAM 역할들

- 웹서버용 IAM 인스턴스 프로파일
- Prometheus 모니터링용 IAM 인스턴스 프로파일
- RDS 모니터링용 IAM 역할

---

## 6. 비활성화된 리소스들

> (현재 Terraform 코드에서 주석 처리되어 있어 배포되지 않음)

- K6 스트레스 테스트 인스턴스
- Auto Scaling Group
- Application Load Balancer

---

## 🏗️ 전체 아키텍처 요약

이 인프라는 **e-commerce 모니터링 시스템**으로 다음으로 구성됩니다:

- 웹 애플리케이션 서버 (EC2)
- 데이터베이스 서버 (RDS MySQL)
- 모니터링 서버 (Prometheus + Grafana)
- 네트워킹 인프라 (VPC, Subnets, Security Groups)

**모든 리소스는 `ecommerce` 네임스페이스로 태그되어 관리됩니다.**
