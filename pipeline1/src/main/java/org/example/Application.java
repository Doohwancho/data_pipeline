package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 이 클래스는 스프링 부트 애플리케이션의 시작점입니다.
 * @SpringBootApplication 어노테이션은 이 클래스가 스프링 부트의
 * 핵심 설정 클래스임을 나타냅니다.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // SpringApplication.run() 메소드를 통해 내장 웹 서버(Tomcat)를 실행합니다.
        SpringApplication.run(Application.class, args);
        System.out.println("서버가 시작되었습니다. http://localhost:8080 에서 확인하세요.");
    }

}
