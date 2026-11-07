package dev.prism.core.policyknowledge.internal;

import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentView;
import dev.prism.core.policy.PolicyDirectory;
import dev.prism.core.policy.PolicyKnowledgeLifecycle;
import dev.prism.core.policy.PolicyKnowledgeLifecycle.VersionState;
import dev.prism.core.policyknowledge.PublishedKnowledge;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultPublishedKnowledge implements PublishedKnowledge {

    private final PolicyDirectory policies;
    private final PolicyKnowledgeLifecycle lifecycle;
    private final DocumentService documents;
    private final KnowledgeReader reader;

    DefaultPublishedKnowledge(
            PolicyDirectory policies, PolicyKnowledgeLifecycle lifecycle, DocumentService documents, KnowledgeReader reader) {
        this.policies = policies;
        this.lifecycle = lifecycle;
        this.documents = documents;
        this.reader = reader;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> snapshot(UUID versionId) {
        if (policies.findVersion(versionId).filter(version -> "PUBLISHED".equals(version.knowledgeStatus())).isEmpty()) {
            return Optional.empty();
        }
        VersionState version = lifecycle.state(versionId);
        Optional<DocumentView> wording = documents.listFor(DocumentOwner.Type.POLICY_VERSION, versionId).stream()
                .filter(document -> document.status() == DocumentView.Status.ACTIVE)
                .findFirst();
        if (wording.isEmpty()) {
            return Optional.empty();
        }
        KnowledgeView view = reader.read(version, wording.get());
        List<Provision> provisions = view.provisions().stream()
                .map(provision -> new Provision(
                        provision.id(),
                        provision.key(),
                        provision.type(),
                        provision.title(),
                        provision.text(),
                        provision.pageStart(),
                        provision.pageEnd(),
                        provision.parentKey(),
                        provision.quote(),
                        provision.region()))
                .toList();
        List<Relation> relations = view.relations().stream()
                .filter(relation -> relation.toProvisionId() != null)
                .map(relation -> new Relation(relation.fromProvisionId(), relation.toProvisionId(), relation.relation()))
                .distinct()
                .filter(relation -> !Objects.equals(relation.fromProvisionId(), relation.toProvisionId()))
                .toList();
        return Optional.of(new Snapshot(versionId, version.policyId(), wording.get().id(), provisions, relations));
    }
}
