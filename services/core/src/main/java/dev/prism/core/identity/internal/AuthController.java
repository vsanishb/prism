package dev.prism.core.identity.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.identity.Permission;
import dev.prism.core.identity.Role;
import dev.prism.core.identity.internal.AccountTokenService.TokenDetails;
import dev.prism.core.platform.security.ActorPrincipal;
import dev.prism.core.platform.security.ClientAddressResolver;
import dev.prism.core.platform.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final SignInService signInService;
    private final AccountTokenService tokenService;
    private final UserRepository users;
    private final SecurityContextRepository contextRepository;
    private final ClientAddressResolver addressResolver;
    private final AuditRecorder audit;
    private final CookieCsrfTokenRepository csrfCookies = CookieCsrfTokenRepository.withHttpOnlyFalse();

    AuthController(
            SignInService signInService,
            AccountTokenService tokenService,
            UserRepository users,
            SecurityContextRepository contextRepository,
            ClientAddressResolver addressResolver,
            AuditRecorder audit) {
        this.signInService = signInService;
        this.tokenService = tokenService;
        this.users = users;
        this.contextRepository = contextRepository;
        this.addressResolver = addressResolver;
        this.audit = audit;
    }

    /** Reading the token forces the deferred CSRF cookie to be written. */
    @GetMapping("/csrf")
    CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/sign-in")
    CurrentUserView signIn(
            @Valid @RequestBody SignInRequest body, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication =
                signInService.authenticate(body.email(), body.password(), addressResolver.resolve(request));

        // A fresh session ID and CSRF token at the privilege boundary prevent fixation.
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        csrfCookies.saveToken(null, request, response);

        PrismUserDetails user = (PrismUserDetails) authentication.getPrincipal();
        signInService.recordSuccess(user);
        return currentUser(user.userId());
    }

    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(HttpServletRequest request, HttpServletResponse response) {
        audit.recordIndependently(
                AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.SIGN_OUT, AuditOutcome.SUCCESS));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfCookies.saveToken(null, request, response);
    }

    @GetMapping("/me")
    CurrentUserView me() {
        ActorPrincipal actor = CurrentActor.require();
        return currentUser(actor.userId());
    }

    @PostMapping("/tokens/inspect")
    TokenDetails inspect(@Valid @RequestBody InspectRequest body) {
        return tokenService.inspect(body.token());
    }

    @PostMapping("/tokens/redeem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void redeem(@Valid @RequestBody RedeemRequest body) {
        tokenService.redeem(body.token(), body.password(), body.fullName());
    }

    private CurrentUserView currentUser(UUID userId) {
        UserEntity user = users.findById(userId).orElseThrow();
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        user.roles().forEach(role -> permissions.addAll(role.permissions()));
        return new CurrentUserView(user.id(), user.email(), user.fullName(), user.roles(), permissions);
    }

    record CsrfView(String headerName, String token) {}

    record SignInRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 256) String password) {}

    record InspectRequest(@NotBlank @Size(max = 128) String token) {}

    record RedeemRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(max = 256) String password,
            @Size(max = 200) String fullName) {}

    record CurrentUserView(
            UUID id, String email, String fullName, Set<Role> roles, Set<Permission> permissions) {}
}
