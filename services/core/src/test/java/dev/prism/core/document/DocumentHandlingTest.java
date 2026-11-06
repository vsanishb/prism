package dev.prism.core.document;

import static dev.prism.core.support.ClaimFixtures.BILL;
import static dev.prism.core.support.ClaimFixtures.read;
import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.identity.Role;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

class DocumentHandlingTest extends IntegrationTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

    private ClaimFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = claimFixtures();
    }

    private static long documentRows(String claimId) throws Exception {
        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM core.documents WHERE owner_id = '" + claimId + "'")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private JsonNode documents(String claimId) throws Exception {
        Client processor = fixtures.processor();
        return processor.bodyOf(processor.getPath("/api/v1/claims/" + claimId)).get("documents");
    }

    @ParameterizedTest
    @CsvSource({
        "claims/appendectomy/hospital-bill.pdf, application/pdf, 1",
        "claims/scanned/prescription.png, image/png, 1",
        "claims/scanned/diagnostic-report.jpg, image/jpeg, 1",
        "claims/scanned/discharge-note.tiff, image/tiff, 2"
    })
    void acceptsSupportedFormatsAndCountsPages(String path, String mediaType, int pages) throws Exception {
        MockHttpServletResponse response =
                fixtures.upload(fixtures.processor(), fixtures.draftClaimId(), "OTHER", "upload", read(path));

        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode document = fixtures.processor().bodyOf(response);
        assertThat(document.get("mediaType").asString()).isEqualTo(mediaType);
        assertThat(document.get("pageCount").asInt()).isEqualTo(pages);
    }

    @ParameterizedTest
    @CsvSource({
        "invalid/spoofed-text-named-as.pdf, unsupported_type",
        "invalid/spoofed-html-named-as.png, unsupported_type",
        "invalid/unsupported-office-file.docx, unsupported_type",
        "invalid/empty.pdf, empty_file",
        "invalid/truncated.pdf, malformed_file",
        "invalid/password-protected.pdf, encrypted_file"
    })
    void refusesUnsafeFilesAndRecordsTheAttempt(String path, String code) throws Exception {
        String claim = fixtures.draftClaimId();
        Client processor = fixtures.processor();

        MockHttpServletResponse response = fixtures.upload(processor, claim, "OTHER", "bill.pdf", read(path));

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(processor.bodyOf(response).get("code").asString()).isEqualTo(code);
        assertThat(documentRows(claim)).isZero();
        String timeline = processor.getPath("/api/v1/claims/" + claim + "/timeline").getContentAsString();
        assertThat(timeline).contains("DOCUMENT_REJECTED");
    }

    @Test
    void refusesFilesOverTheSizeLimit() throws Exception {
        byte[] oversized = Arrays.copyOf(read(BILL), 26 * 1024 * 1024);

        MockHttpServletResponse response =
                fixtures.upload(fixtures.processor(), fixtures.draftClaimId(), "OTHER", "large.pdf", oversized);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(fixtures.processor().bodyOf(response).get("code").asString()).isEqualTo("file_too_large");
    }

    @Test
    void refusesAPathInTheFilenameButKeepsADisplayName() throws Exception {
        MockHttpServletResponse response = fixtures.upload(
                fixtures.processor(), fixtures.draftClaimId(), "OTHER", "..\\..\\windows\\evil\".pdf", read(BILL));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(fixtures.processor().bodyOf(response).get("originalFilename").asString()).isEqualTo("evil.pdf");
    }

    @Test
    void sameFileTwiceOnAClaimIsRefusedButAllowedOnAnother() throws Exception {
        String first = fixtures.draftClaimId();
        fixtures.uploadBill(first);

        MockHttpServletResponse duplicate = fixtures.upload(fixtures.processor(), first, "OTHER", "copy.pdf", read(BILL));
        assertThat(duplicate.getStatus()).isEqualTo(409);
        assertThat(fixtures.processor().bodyOf(duplicate).get("code").asString()).isEqualTo("duplicate_document");

        assertThat(fixtures.upload(fixtures.processor(), fixtures.draftClaimId(), "OTHER", "copy.pdf", read(BILL)).getStatus())
                .isEqualTo(201);
    }

    @Test
    void policyWordingIsNotAClaimDocumentType() throws Exception {
        assertThat(fixtures.upload(fixtures.processor(), fixtures.draftClaimId(), "POLICY_WORDING", "x.pdf", read(BILL)).getStatus())
                .isEqualTo(422);
    }

    @Test
    void rendersPagesOfEveryFormatAsPng() throws Exception {
        Client processor = fixtures.processor();
        String claim = fixtures.draftClaimId();
        for (String path : new String[] {BILL, "claims/scanned/prescription.png", "claims/scanned/discharge-note.tiff"}) {
            String document = processor.bodyOf(fixtures.upload(processor, claim, "OTHER", "f", read(path))).get("id").asString();

            MockHttpServletResponse page = processor.getPath("/api/v1/documents/" + document + "/pages/1");

            assertThat(page.getStatus()).isEqualTo(200);
            assertThat(page.getContentType()).isEqualTo("image/png");
            assertThat(Arrays.copyOf(page.getContentAsByteArray(), 4)).isEqualTo(PNG_SIGNATURE);
            assertThat(page.getHeader("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void missingPagesAreNotFound() throws Exception {
        String document = fixtures.uploadBill(fixtures.draftClaimId());

        assertThat(fixtures.processor().getPath("/api/v1/documents/" + document + "/pages/2").getStatus()).isEqualTo(404);
        assertThat(fixtures.processor().getPath("/api/v1/documents/" + document + "/pages/0").getStatus()).isEqualTo(404);
    }

    @Test
    void downloadIsAnAttachmentAndReturnsTheOriginalBytes() throws Exception {
        String document = fixtures.uploadBill(fixtures.draftClaimId());

        MockHttpServletResponse response = fixtures.processor().getPath("/api/v1/documents/" + document + "/content");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getContentAsByteArray()).isEqualTo(read(BILL));
    }

    @Test
    void readsAreAudited() throws Exception {
        String claim = fixtures.submittedClaimId();
        String document = documents(claim).get(0).get("id").asString();
        Client reviewer = signedInAs(Role.REVIEWER);
        reviewer.getPath("/api/v1/documents/" + document + "/pages/1");
        reviewer.getPath("/api/v1/documents/" + document + "/content");

        Client auditor = signedInAs(Role.AUDITOR);
        JsonNode viewed = auditor.bodyOf(auditor.getPath("/api/v1/audit/events?action=DOCUMENT_VIEWED&actorId=" + reviewer.userId));
        JsonNode downloaded = auditor.bodyOf(auditor.getPath("/api/v1/audit/events?action=DOCUMENT_DOWNLOADED&actorId=" + reviewer.userId));

        assertThat(viewed.get("items").get(0).get("details").get("PAGE").asString()).isEqualTo("1");
        assertThat(viewed.get("items").get(0).get("targetId").asString()).isEqualTo(document);
        assertThat(downloaded.get("items")).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"REVIEWER, 200", "AUDITOR, 200", "FRAUD_INVESTIGATOR, 200", "ADMIN, 403", "POLICY_MANAGER, 403"})
    void readingClaimDocumentsFollowsRoles(Role role, int expected) throws Exception {
        String document = documents(fixtures.submittedClaimId()).get(0).get("id").asString();

        Client client = signedInAs(role);

        assertThat(client.getPath("/api/v1/documents/" + document + "/pages/1").getStatus()).isEqualTo(expected);
        assertThat(client.getPath("/api/v1/documents/" + document + "/content").getStatus()).isEqualTo(expected);
    }

    @Test
    void draftDocumentsAreHiddenOutsideIntake() throws Exception {
        String document = fixtures.uploadBill(fixtures.draftClaimId());
        Client reviewer = signedInAs(Role.REVIEWER);

        assertThat(reviewer.getPath("/api/v1/documents/" + document).getStatus()).isEqualTo(404);
        assertThat(reviewer.getPath("/api/v1/documents/" + document + "/pages/1").getStatus()).isEqualTo(404);
        assertThat(reviewer.getPath("/api/v1/documents/" + document + "/content").getStatus()).isEqualTo(404);
    }

    @Test
    void documentsNeedASession() throws Exception {
        String document = fixtures.uploadBill(fixtures.draftClaimId());

        assertThat(anonymous().getPath("/api/v1/documents/" + document + "/content").getStatus()).isEqualTo(401);
    }

    @Test
    void onlyProcessorsUpload() throws Exception {
        String claim = fixtures.submittedClaimId();

        assertThat(fixtures.upload(signedInAs(Role.REVIEWER), claim, "OTHER", "x.png", read("claims/scanned/prescription.png")).getStatus())
                .isEqualTo(403);
    }

    @Test
    void replacementSupersedesAndKeepsTheOriginal() throws Exception {
        String claim = fixtures.draftClaimId();
        String original = fixtures.uploadBill(claim);
        Client processor = fixtures.processor();

        MockHttpServletResponse response = processor.upload(
                "/api/v1/claims/" + claim + "/documents/" + original + "/replacement",
                "corrected.pdf",
                read("claims/appendectomy/claim-form.pdf"));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(processor.bodyOf(response).get("supersedesId").asString()).isEqualTo(original);
        assertThat(processor.bodyOf(response).get("documentType").asString()).isEqualTo("HOSPITAL_BILL");
        assertThat(ClaimFixtures.values(documents(claim), "status")).containsExactly("SUPERSEDED", "ACTIVE");
        assertThat(processor.getPath("/api/v1/documents/" + original + "/content").getContentAsByteArray()).isEqualTo(read(BILL));
    }

    @Test
    void aFailedReplacementLeavesTheOriginalActive() throws Exception {
        String claim = fixtures.draftClaimId();
        String original = fixtures.uploadBill(claim);

        MockHttpServletResponse response = fixtures.processor().upload(
                "/api/v1/claims/" + claim + "/documents/" + original + "/replacement",
                "bad.pdf",
                read("invalid/truncated.pdf"));

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(ClaimFixtures.values(documents(claim), "status")).containsExactly("ACTIVE");
    }

    @Test
    void removalWorksOnlyOnDrafts() throws Exception {
        String draft = fixtures.draftClaimId();
        String document = fixtures.uploadBill(draft);
        Client processor = fixtures.processor();

        assertThat(processor.send(MockMvcRequestBuilders.delete("/api/v1/claims/" + draft + "/documents/" + document)).getStatus())
                .isEqualTo(204);
        assertThat(ClaimFixtures.values(documents(draft), "status")).containsExactly("REMOVED");
        assertThat(processor.bodyOf(processor.getPath("/api/v1/claims/" + draft)).get("submissionIssues").toString()).contains("document");

        String submitted = fixtures.submittedClaimId();
        String kept = documents(submitted).get(0).get("id").asString();
        assertThat(processor.send(MockMvcRequestBuilders.delete("/api/v1/claims/" + submitted + "/documents/" + kept)).getStatus())
                .isEqualTo(409);
    }

    @Test
    void submittedClaimsAcceptAdditionsAndClosedClaimsDoNot() throws Exception {
        String claim = fixtures.submittedClaimId();
        Client processor = fixtures.processor();
        byte[] scan = read("claims/scanned/prescription.png");

        assertThat(fixtures.upload(processor, claim, "PRESCRIPTION", "scan.png", scan).getStatus()).isEqualTo(201);

        processor.postJson("/api/v1/claims/" + claim + "/withdrawal", Map.of("reason", "CLAIMANT_REQUEST"));
        assertThat(fixtures.upload(processor, claim, "OTHER", "late.jpg", read("claims/scanned/diagnostic-report.jpg")).getStatus())
                .isEqualTo(409);
    }

    @Test
    void cannotActOnAnotherClaimsDocument() throws Exception {
        String document = fixtures.uploadBill(fixtures.draftClaimId());
        String other = fixtures.draftClaimId();

        assertThat(fixtures.processor().send(MockMvcRequestBuilders.delete("/api/v1/claims/" + other + "/documents/" + document)).getStatus())
                .isEqualTo(404);
    }

    @Test
    void uploadsNeedACsrfToken() throws Exception {
        String claim = fixtures.draftClaimId();

        MockHttpServletResponse response = fixtures.processor().uploadWithoutCsrf(
                "/api/v1/claims/" + claim + "/documents?documentType=OTHER", "bill.pdf", read(BILL));

        assertThat(response.getStatus()).isEqualTo(403);
    }
}
