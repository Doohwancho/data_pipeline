package org.example.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이 클래스는 웹 요청을 처리하는 컨트롤러입니다.
 * @RestController 어노테이션은 이 클래스의 모든 메소드가
 * 뷰(View) 대신 문자열이나 JSON 같은 데이터를 직접 반환함을 의미합니다.
 */
@RestController
public class HelloWorldController {

    /**
     * HTTP GET 요청을 처리하는 메소드입니다.
     * @GetMapping("/")는 웹사이트의 루트 경로(예: http://localhost:8080/)로
     * 오는 GET 요청을 이 메소드와 매핑합니다.
     * @return "Hello World" 문자열을 HTTP 응답 본문으로 반환합니다.
     */
    @GetMapping("/")
    public String sayHello() {
        return "Hello World";
    }
}