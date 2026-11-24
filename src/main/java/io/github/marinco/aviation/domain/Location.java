package io.github.marinco.aviation.domain;

/** Where an airport is. {@code elevationM} is metres above sea level. */
public record Location(String region, String country, Double latitude, Double longitude, Integer elevationM) {}
