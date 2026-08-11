package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.rag.ingest.VueIngestionExpectedSnapshot;
import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import com.lyw.appgeneration.rag.ingest.VuePgVectorIngestionVerifier;
import com.lyw.appgeneration.rag.ingest.VuePgVectorTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueIngestionVerificationApiTest {

    @Test
    void 检索包可使用真实核验实例接口与公开结果() throws Exception {
        VuePgVectorIngestionVerifier verifier =
                new VuePgVectorIngestionVerifier(new ObjectMapper());
        VueIngestionVerification result = new VueIngestionVerification(
                false, "catalog-version", 23, 0, 0, Set.of(), List.of("固定问题"));

        assertNotNull(verifier);
        assertFalse(result.passed());
        assertTrue(Modifier.isPublic(VuePgVectorIngestionVerifier.class.getModifiers()));
        assertTrue(Modifier.isPublic(VueIngestionVerification.class.getModifiers()));
        assertTrue(Modifier.isPublic(VuePgVectorIngestionVerifier.class.getMethod(
                "verify",
                VueIngestionExpectedSnapshot.class,
                VuePgVectorTarget.class,
                String.class).getModifiers()));
    }
}
