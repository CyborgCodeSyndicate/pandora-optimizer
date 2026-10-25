package io.cyborgcode.pandora.model;

import java.util.List;

public record ScanRequest(
        int protocolVersion,
        String workspaceId,
        String moduleId,
        String root,
        List<String> sourceRoots,
        List<String> references) {}
