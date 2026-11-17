package com.forecast.task.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 当前服务节点标识，写入任务执行记录与触发记录，便于集群排障。
 */
@Getter
@Component
public class NodeIdentity {

    private final String nodeId;

    public NodeIdentity(ForecastProperties properties, @Value("${server.port:8080}") int port) {
        if (StringUtils.hasText(properties.getNodeId())) {
            this.nodeId = properties.getNodeId();
        } else {
            this.nodeId = resolveHost() + ":" + port;
        }
    }

    private static String resolveHost() {
        String env = System.getenv("HOSTNAME");
        if (StringUtils.hasText(env)) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
