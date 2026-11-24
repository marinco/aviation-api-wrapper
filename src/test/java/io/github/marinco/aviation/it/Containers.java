package io.github.marinco.aviation.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Containers shared by every integration test class; started once per JVM. */
final class Containers {

    static final GenericContainer<?> WIREMOCK = new GenericContainer<>(
                    DockerImageName.parse("wiremock/wiremock:3.13.2"))
            .withExposedPorts(8080)
            .withCommand("--disable-banner")
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    static {
        WIREMOCK.start();
        REDIS.start();
    }

    private Containers() {}

    static String wireMockUrl() {
        return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080);
    }
}
