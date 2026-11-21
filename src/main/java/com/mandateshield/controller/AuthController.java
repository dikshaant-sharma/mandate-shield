package com.mandateshield.controller;

import com.mandateshield.domain.UserDocument;
import com.mandateshield.repository.UserRepository;
import com.mandateshield.service.JwtService;
import com.mandateshield.service.LoginRateLimiter;
import com.mandateshield.service.UserSequenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "12345678", "123456789", "qwerty123", "password123",
            "admin123", "welcome123", "pass1234", "letmein123"
    );

    private final UserRepository users;
    private final UserSequenceService sequence;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final LoginRateLimiter rateLimiter;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Session register(@Valid @RequestBody RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        validatePasswordStrength(request.password());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        UserDocument user = UserDocument.builder()
                .id(sequence.nextUserId())
                .email(email)
                .fullName(sanitizeInput(request.fullName()))
                .passwordHash(passwords.encode(request.password()))
                .lastUserActivityDate(Instant.now())
                .build();
        return session(users.save(user));
    }

    @PostMapping("/login")
    public Session login(@Valid @RequestBody LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (rateLimiter.isBlocked(email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Account temporarily locked for 15 minutes.");
        }

        UserDocument user = users.findByEmailIgnoreCase(email)
                .filter(saved -> passwords.matches(request.password(), saved.getPasswordHash()))
                .orElse(null);

        if (user == null) {
            rateLimiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }

        rateLimiter.recordSuccess(email);
        return session(user);
    }

    @GetMapping("/me")
    public UserView me(java.security.Principal principal) {
        UserDocument user = users.findById(Long.valueOf(principal.getName())).orElseThrow();
        return view(user);
    }

    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters long.");
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is too common and easily guessed. Please choose a stronger password.");
        }
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (char c : password.toCharArray()) {
            if (Character.isDigit(c)) hasDigit = true;
            if (Character.isLetter(c)) hasLetter = true;
        }
        if (!hasDigit || !hasLetter) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain at least one letter and one number.");
        }
    }

    private String sanitizeInput(String input) {
        if (input == null) return "";
        return input.trim().replaceAll("[<>\"']", "");
    }

    private Session session(UserDocument user) { return new Session(jwt.issue(user), view(user)); }
    private UserView view(UserDocument user) { return new UserView(user.getId(), user.getFullName(), user.getEmail()); }
    public record RegisterRequest(@NotBlank @Size(max=80) String fullName, @NotBlank @Email @Size(max=254) String email, @NotBlank @Size(min=8,max=72) String password) {}
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
    public record Session(String token, UserView user) {}
    public record UserView(Long id, String name, String email) {}
}
