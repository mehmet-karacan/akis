package tr.com.innova.akis.export;

import java.io.IOException;

import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportRequest;

/**
 * Service-provider interface for allowlisted export datasets.
 */
public interface ExportProvider {

    /**
     * Returns true when this provider can handle the exact combination.
     */
    boolean supports(String providerId, String resourceId);

    /**
     * Validates the request for this provider/resource. Throws ExportException
     * with a 422 problem detail on validation failure.
     */
    void validate(ExportRequest request);

    /**
     * Streams records into the writer. Implementations must be bounded: no
     * in-memory materialisation of the whole dataset.
     */
    void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException, ExportException;
}
