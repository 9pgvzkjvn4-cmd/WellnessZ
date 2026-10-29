package com.wellnessz.bff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiProxyPathTest {

    @Test
    void increment2WorkspaceResourcesKeepTheirPrefixWhileLegacyAliasesAreStripped() {
        assertThat(ApiProxyController.isCoreWorkspaceResource("/workspace/team-restrictions")).isTrue();
        assertThat(ApiProxyController.isCoreWorkspaceResource("/workspace/duty-proposals")).isTrue();
        assertThat(ApiProxyController.isCoreWorkspaceResource("/workspace/documents")).isTrue();
        assertThat(ApiProxyController.isCoreWorkspaceResource("/workspace/cases")).isFalse();
        assertThat(ApiProxyController.isCoreWorkspaceResource("/workspace/documentsX")).isFalse();
    }
}
