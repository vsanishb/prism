package dev.prism.core.document.internal;

import dev.prism.core.document.DocumentView;
import dev.prism.core.document.internal.DocumentReadService.Content;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
class DocumentController {

    private final DocumentReadService service;

    DocumentController(DocumentReadService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    DocumentView details(@PathVariable UUID id) {
        return service.details(id);
    }

    @GetMapping(value = "/{id}/pages/{page}", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> page(@PathVariable UUID id, @PathVariable int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.renderPage(id, page));
    }

    /** Always an attachment: uploaded content must never be rendered by the browser on our origin. */
    @GetMapping("/{id}/content")
    ResponseEntity<Resource> content(@PathVariable UUID id) {
        Content content = service.download(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(content.path()));
    }
}
