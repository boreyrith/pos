package com.pos.shop;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.ClientInfo;
import com.pos.common.ApiException;
import com.pos.marketing.PromotionDtos.EvaluateResponse;
import com.pos.shop.CustomerAuthService.AccountDto;
import com.pos.shop.ShopService.CategoryDto;
import com.pos.shop.ShopService.OrderSummary;
import com.pos.shop.ShopService.OrderView;
import com.pos.shop.ShopService.PlaceOrderRequest;
import com.pos.shop.ShopService.PlacedOrder;
import com.pos.shop.ShopService.ProductDto;
import com.pos.shop.ShopService.ProductPage;
import com.pos.shop.ShopService.PromoRequest;
import com.pos.shop.ShopService.StoreDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The online shop's API, for customers (frontend-buy). Open to everyone (SecurityConfig); a signed-in
 * customer is known by their shop cookie (CustomerAuthService), never by a staff sign-in. A guest
 * reaches their order with the key of its private link ({@code ?key=}).
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    public record SignUpRequest(@NotBlank @Size(max = 150) String name, @NotBlank @Size(max = 30) String phone,
                                @NotBlank @Size(max = 100) String password) {
    }

    public record SignInRequest(@NotBlank @Size(max = 30) String phone, @NotBlank @Size(max = 100) String password) {
    }

    private final ShopService shop;
    private final CustomerAuthService auth;

    public ShopController(ShopService shop, CustomerAuthService auth) {
        this.shop = shop;
        this.auth = auth;
    }

    @GetMapping("/store")
    public StoreDto store() {
        return shop.store();
    }

    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return shop.categories();
    }

    @GetMapping("/products")
    public ProductPage products(@RequestParam Long branchId, @RequestParam(required = false) String q,
                                @RequestParam(required = false) Long categoryId, @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "24") int size) {
        return shop.products(branchId, q, categoryId, page, size);
    }

    @GetMapping("/products/{id}")
    public ProductDto product(@PathVariable Long id, @RequestParam Long branchId) {
        return shop.product(id, branchId);
    }

    @PostMapping("/promotions")
    public EvaluateResponse promotions(@RequestBody PromoRequest req, HttpServletRequest request) {
        return shop.promotions(req, auth.current(request).orElse(null), ClientInfo.from(request).ip());
    }

    @PostMapping("/orders")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public PlacedOrder place(@RequestBody PlaceOrderRequest req, HttpServletRequest request) {
        return shop.place(req, auth.current(request).orElse(null), ClientInfo.from(request).ip());
    }

    @GetMapping("/orders/{number}")
    public OrderView order(@PathVariable String number, @RequestParam(required = false) String key, HttpServletRequest request) {
        return shop.order(number, key, auth.current(request).orElse(null));
    }

    @PostMapping("/orders/{number}/payment/check")
    public OrderView checkPayment(@PathVariable String number, @RequestParam(required = false) String key, HttpServletRequest request) {
        return shop.checkPayment(number, key, auth.current(request).orElse(null), ClientInfo.from(request).ip());
    }

    /** Test mode only: pays the order as if the customer had scanned the code. */
    @PostMapping("/orders/{number}/payment/simulate")
    public OrderView simulatePayment(@PathVariable String number, @RequestParam(required = false) String key, HttpServletRequest request) {
        return shop.simulatePayment(number, key, auth.current(request).orElse(null));
    }

    /** The customer cancels an order that is still waiting for payment. */
    @PostMapping("/orders/{number}/cancel")
    public OrderView cancel(@PathVariable String number, @RequestParam(required = false) String key, HttpServletRequest request) {
        return shop.cancel(number, key, auth.current(request).orElse(null));
    }

    // ---------------------------------------------------------------- customer accounts

    @PostMapping("/account/signup")
    public ResponseEntity<AccountDto> signUp(@Valid @RequestBody SignUpRequest req, HttpServletRequest request) {
        var signedIn = auth.signUp(req.name(), req.phone(), req.password(), ClientInfo.from(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, signedIn.cookie().toString()).body(signedIn.account());
    }

    @PostMapping("/account/signin")
    public ResponseEntity<AccountDto> signIn(@Valid @RequestBody SignInRequest req, HttpServletRequest request) {
        var signedIn = auth.signIn(req.phone(), req.password(), ClientInfo.from(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, signedIn.cookie().toString()).body(signedIn.account());
    }

    @PostMapping("/account/signout")
    public ResponseEntity<Void> signOut(HttpServletRequest request) {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, auth.signOut(request).toString()).build();
    }

    /** The signed-in customer; 401 when nobody is signed in on this browser. */
    @GetMapping("/account")
    public AccountDto account(HttpServletRequest request) {
        return AccountDto.from(signedIn(request));
    }

    @GetMapping("/account/orders")
    public List<OrderSummary> accountOrders(@RequestParam(defaultValue = "0") int page, HttpServletRequest request) {
        return shop.accountOrders(signedIn(request), page);
    }

    private CustomerAccount signedIn(HttpServletRequest request) {
        return auth.current(request).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }
}
