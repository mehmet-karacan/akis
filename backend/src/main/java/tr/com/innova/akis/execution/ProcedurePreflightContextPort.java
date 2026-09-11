package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

interface ProcedurePreflightContextPort {

    Optional<Context> find(UUID projectUuid, UUID publicationUuid);

    record Context(
            UUID projectUuid,
            UUID publicationUuid,
            String status,
            String releaseHash,
            String scenarioPlanHash,
            JsonNode scenarioPlan,
            JsonNode physicalManifest) {

        public Context {
            scenarioPlan = scenarioPlan == null ? null : scenarioPlan.deepCopy();
            physicalManifest = physicalManifest == null
                    ? null : physicalManifest.deepCopy();
        }

        @Override
        public JsonNode scenarioPlan() {
            return scenarioPlan == null ? null : scenarioPlan.deepCopy();
        }

        @Override
        public JsonNode physicalManifest() {
            return physicalManifest == null ? null : physicalManifest.deepCopy();
        }
    }
}
