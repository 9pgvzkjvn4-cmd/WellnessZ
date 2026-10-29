package com.wellnessz.integration.connector;

import java.util.List;

/**
 * Describes an enterprise / external integration as shown in the logical architecture. The data contract makes
 * explicit what may cross the boundary (e.g. rostering receives fitness status + dates only).
 */
public record ConnectorDescriptor(String id, String system, Direction direction, String protocol, List<String> dataContract,
                                  String disclosure) {

    public enum Direction { IN, OUT, BOTH }
}
