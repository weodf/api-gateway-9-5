package org.imooc.backend.http.server.controller;

import lombok.extern.slf4j.Slf4j;
import org.imooc.gateway.client.core.ApiInvoker;
import org.imooc.gateway.client.core.ApiProperties;
import org.imooc.gateway.client.core.ApiProtocol;
import org.imooc.gateway.client.core.ApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@ApiService(serviceId = "backend-http-server", protocol = ApiProtocol.HTTP, patternPath = "/http-server/**")
public class PingController {

    @Autowired
    private ApiProperties apiProperties;

    @ApiInvoker(path = "/http-server/ping")
    @GetMapping("/http-server/ping")
    public String ping() throws InterruptedException {
        Thread.sleep(800);
        return "pong";
    }

    @ApiInvoker(path = "/http-server/ping1")
    @GetMapping("/http-server/ping1")
    public String ping1() {
        return "pong1";
    }

    @ApiInvoker(path = "/http-server/ping2")
    @GetMapping("/http-server/ping2")
    public String ping2() {
        return "pong2";
    }
}
