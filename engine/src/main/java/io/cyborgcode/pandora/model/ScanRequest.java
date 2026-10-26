package io.cyborgcode.pandora.model;

import java.util.List;

/**
 * Versioned request sent to a language adapter with workspace-relative inputs.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record ScanRequest(
      int protocolVersion,
      String workspaceId,
      String moduleId,
      String root,
      List<String> sourceRoots,
      List<String> references) {}
