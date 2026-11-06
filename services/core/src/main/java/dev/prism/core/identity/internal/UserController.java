package dev.prism.core.identity.internal;

import dev.prism.core.identity.Role;
import dev.prism.core.identity.internal.AccountTokenService.IssuedLink;
import dev.prism.core.identity.internal.AccountTokenService.PendingInvitation;
import dev.prism.core.identity.internal.UserAdministrationService.UserView;
import dev.prism.core.platform.web.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserAdministrationService users;
    private final AccountTokenService tokens;

    UserController(UserAdministrationService users, AccountTokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @GetMapping("/users")
    Page<UserView> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return users.list(search, cursor, Math.clamp(limit, 1, MAX_PAGE_SIZE));
    }

    @PutMapping("/users/{id}/roles")
    UserView changeRoles(@PathVariable UUID id, @Valid @RequestBody RolesRequest body) {
        return users.changeRoles(id, body.roles());
    }

    @PostMapping("/users/{id}/deactivation")
    UserView deactivate(@PathVariable UUID id) {
        return users.deactivate(id);
    }

    @PostMapping("/users/{id}/reactivation")
    UserView reactivate(@PathVariable UUID id) {
        return users.reactivate(id);
    }

    @PostMapping("/users/{id}/password-reset")
    IssuedLink issuePasswordReset(@PathVariable UUID id) {
        return tokens.issuePasswordReset(id);
    }

    @GetMapping("/invitations")
    List<PendingInvitation> invitations() {
        return tokens.pendingInvitations();
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedLink invite(@Valid @RequestBody InvitationRequest body) {
        return tokens.invite(body.email(), body.fullName(), body.roles());
    }

    @DeleteMapping("/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        tokens.revokeInvitation(id);
    }

    record RolesRequest(@NotEmpty(message = "Choose at least one role.") Set<Role> roles) {}

    record InvitationRequest(
            @NotBlank @Email(message = "Enter a valid email address.") @Size(max = 320) String email,
            @NotBlank(message = "Enter a name.") @Size(max = 200) String fullName,
            @NotEmpty(message = "Choose at least one role.") Set<Role> roles) {}
}
