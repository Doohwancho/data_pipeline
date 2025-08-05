plugins {
    // 스프링 부트 플러그인 추가
    id("org.springframework.boot") version "2.7.18"
    // 스프링 의존성 관리 플러그인 추가
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

// 자바 버전을 1.8로 설정
java.sourceCompatibility = JavaVersion.VERSION_1_8

repositories {
    mavenCentral()
}

dependencies {
    // 내장 웹 서버(Tomcat)를 포함한 스프링 웹 스타터 의존성 추가
    implementation("org.springframework.boot:spring-boot-starter-web")
    
    // 테스트 의존성
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
