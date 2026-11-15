package com.pos.shop;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.AuditService.Actor;
import com.pos.auth.ClientInfo;
import com.pos.auth.SignInThrottle;
import com.pos.common.ApiException;
import com.pos.common.AppProperties;
import com.pos.customer.Customer;
import com.pos.customer.CustomerRepository;
import com.pos.customer.CustomerService;
import com.pos.customer.CustomerService.CustomerRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shop customers' accounts and sign-in. Kept apart from staff sign-in: its own table, its own
 * HttpOnly cookie ({@link #COOKIE}, sent only to /api/shop) and no access to staff endpoints.
 * Sign-in is limited per address (as for staff) and an account locks after repeated wrong passwords.
 */
@Service
public class CustomerAuthService {

    static final String COOKIE = "pos_shop";
    static final Duration SESSION = Duration.ofDays(30);

    /**
     * The signed-in customer, as the shop shows them. {@code verified}: their orders get their customer
     * record's points and member offers (otherwise they confirm it at the counter).
     */
    public record AccountDto(Long id, String name, String phone, boolean verified) {
        static AccountDto from(CustomerAccount a) {
            return new AccountDto(a.getId(), a.getCustomer().getName(), a.getPhone(), a.isVerified());
        }
    }

    /** A new session: the account and the cookie to set. */
    public record SignedIn(AccountDto account, ResponseCookie cookie) {
    }

    private final CustomerAccountRepository accounts;
    private final CustomerSessionRepository sessions;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final ShopActor actor;
    private final SignInThrottle throttle;
    private final ShopThrottle shopThrottle;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final AppProperties props;
    private final String dummyHash;

    public CustomerAuthService(CustomerAccountRepository accounts, CustomerSessionRepository sessions, CustomerRepository customers,
                               CustomerService customerService, ShopActor actor, SignInThrottle throttle, ShopThrottle shopThrottle,
                               PasswordEncoder encoder,
                               AuditService audit, AppProperties props) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.customers = customers;
        this.customerService = customerService;
        this.actor = actor;
        this.throttle = throttle;
        this.shopThrottle = shopThrottle;
        this.encoder = encoder;
        this.audit = audit;
        this.props = props;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /**
     * A new account. With a phone no customer on file has, a customer record is made for it (confirmed).
     * With a phone already on file (from the till), the account is on that record but unconfirmed until
     * staff check it at the counter: phones can't be verified yet, and otherwise anyone could take over a
     * member's number. Staff can also remove an account, e.g. when someone else registered the number.
     */
    @Transactional
    public SignedIn signUp(String name, String rawPhone, String password, ClientInfo client) {
        shopThrottle.check(ShopThrottle.Limit.SIGN_UP, client.ip(), Instant.now());
        String phone = ShopKeys.phone(rawPhone);
        requirePassword(password);
        if (accounts.existsByPhone(phone)) {
            throw ApiException.conflict("There is already an account with this phone number. Sign in instead.");
        }
        Customer customer = customers.findByPhone(phone).orElse(null);
        boolean verified = customer == null;
        if (customer == null) {
            Long id = customerService.create(new CustomerRequest(name.trim(), phone, null, null, null, null, true),
                    actor.at(null)).id();
            customer = customers.findById(id).orElseThrow();
        } else if (!customer.isActive()) {
            throw ApiException.badRequest("This phone number can't be used for an account. Please contact the shop.");
        }
        CustomerAccount account = accounts.save(new CustomerAccount(customer, phone, encoder.encode(password), verified));
        audit.logAlways(new Actor(null, phone, null), AuditAction.CUSTOMER_ACCOUNT_CREATED, "Customer", customer.getId(),
                "Online shop account made for " + customer.getName() + " (" + phone + ")"
                        + (verified ? "" : "; on a customer already on file, to be confirmed at the counter"));
        return open(account, client);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SignedIn signIn(String rawPhone, String password, ClientInfo client) {
        Instant now = Instant.now();
        String ip = client.ip();
        long wait = throttle.retryAfter(ip, now);
        if (wait > 0) {
            long minutes = Math.max(1, (wait + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts from this device. Try again in " + minutes
                    + " minute" + (minutes == 1 ? "" : "s") + ".", "TOO_MANY_ATTEMPTS");
        }
        String phone;
        try {
            phone = ShopKeys.phone(rawPhone);
        } catch (ApiException e) {
            throw wrongPassword();
        }
        CustomerAccount found = accounts.findByPhone(phone).orElse(null);
        if (found == null) {
            // Check a password anyway, so an unknown number takes as long as a wrong password.
            encoder.matches(password, dummyHash);
            throttle.failed(ip, now);
            throw wrongPassword();
        }
        CustomerAccount account = accounts.lockById(found.getId()).orElseThrow();
        if (account.isLocked(now)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Too many wrong passwords. Try again in "
                    + (Duration.between(now, account.getLockedUntil()).toMinutes() + 1) + " minutes.");
        }
        if (!encoder.matches(password, account.getPasswordHash())) {
            throttle.failed(ip, now);
            account.setFailedLogins(account.getFailedLogins() + 1);
            if (account.getFailedLogins() >= CustomerAccount.MAX_FAILURES) {
                account.setFailedLogins(0);
                account.setLockedUntil(now.plus(Duration.ofMinutes(CustomerAccount.LOCK_MINUTES)));
                throw new ApiException(HttpStatus.FORBIDDEN, "Too many wrong passwords. Your account is locked for "
                        + CustomerAccount.LOCK_MINUTES + " minutes.");
            }
            throw wrongPassword();
        }
        if (!account.getCustomer().isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account can't be used. Please contact the shop.");
        }
        account.setFailedLogins(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        return open(account, client);
    }

    /** Ends this browser's session; the cookie is cleared either way. */
    @Transactional
    public ResponseCookie signOut(HttpServletRequest request) {
        String token = token(request);
        if (token != null) {
            sessions.findById(ShopKeys.hash(token)).ifPresent(s -> s.setEndedAt(Instant.now()));
        }
        return cookie("", Duration.ZERO);
    }

    /** The account signed in on this request, if any (an ended or expired session counts as none). */
    @Transactional
    public Optional<CustomerAccount> current(HttpServletRequest request) {
        String token = token(request);
        if (token == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return sessions.findWithAccountByTokenHash(ShopKeys.hash(token))
                .filter(s -> s.isActive(now) && s.getAccount().getCustomer().isActive())
                .map(s -> {
                    // Used at most once a minute is enough to show when it was last seen.
                    if (s.getLastUsedAt().isBefore(now.minusSeconds(60))) {
                        s.setLastUsedAt(now);
                    }
                    return s.getAccount();
                });
    }

    /** Housekeeping: sessions ended or expired more than a month ago. */
    @Transactional
    public int deleteOldSessions() {
        return sessions.deleteOld(Instant.now().minus(Duration.ofDays(30)));
    }

    private SignedIn open(CustomerAccount account, ClientInfo client) {
        String raw = ShopKeys.newKey();
        sessions.save(new CustomerSession(ShopKeys.hash(raw), account, Instant.now().plus(SESSION), client.ip(), client.userAgent()));
        return new SignedIn(AccountDto.from(account), cookie(raw, SESSION));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(props.jwt().refreshCookieSecure())
                .sameSite("Lax")
                .path("/api/shop")
                .maxAge(maxAge)
                .build();
    }

    private static String token(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (c.getName().equals(COOKIE) && !c.getValue().isEmpty()) {
                return c.getValue();
            }
        }
        return null;
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 100) {
            throw ApiException.badRequest("Use a password of at least 8 characters.");
        }
    }

    private static ApiException wrongPassword() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Wrong phone number or password");
    }
}
