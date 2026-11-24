package io.github.marinco.aviation.domain;

/** A runway. Length and width are in feet and may be unknown (null). */
public record Runway(String id, Integer lengthFt, Integer widthFt, Surface surface) {

    public enum Surface {
        ASPHALT,
        CONCRETE,
        UNKNOWN
    }
}
