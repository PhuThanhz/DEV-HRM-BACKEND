package vn.system.app.modules.auth.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.auth.domain.request.ReqChangePasswordDTO;
import vn.system.app.modules.auth.domain.request.ReqLoginDTO;
import vn.system.app.modules.auth.domain.response.ResLoginDTO;
import vn.system.app.modules.auth.service.AuthLoginRateLimitService;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.domain.response.ResCreateUserDTO;
import vn.system.app.modules.user.service.UserService;
import vn.system.app.modules.usersession.service.UserSessionService;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final SecurityUtil securityUtil;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService userSessionService;
    private final AuthLoginRateLimitService authLoginRateLimitService;

    @Value("${lotusgroup.jwt.refresh-token-validity-in-seconds}")
    private long refreshTokenExpiration;

    public AuthController(
            AuthenticationManagerBuilder authenticationManagerBuilder,
            SecurityUtil securityUtil,
            UserService userService,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessionService,
            AuthLoginRateLimitService authLoginRateLimitService) {
        this.authenticationManagerBuilder = authenticationManagerBuilder;
        this.securityUtil = securityUtil;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.userSessionService = userSessionService;
        this.authLoginRateLimitService = authLoginRateLimitService;
    }

    // ── HELPER: map UserInfo entity → DTO ──────────────────────────────────────
    private ResLoginDTO.UserInfo mapUserInfo(User user) {
        if (user.getUserInfo() == null)
            return null;
        var info = user.getUserInfo();
        ResLoginDTO.UserInfo dto = new ResLoginDTO.UserInfo();
        dto.setEmployeeCode(info.getEmployeeCode());
        dto.setPhone(info.getPhone());
        dto.setDateOfBirth(info.getDateOfBirth());
        dto.setGender(info.getGender() != null ? info.getGender().name() : null);
        dto.setStartDate(info.getStartDate());
        dto.setContractSignDate(info.getContractSignDate());
        dto.setContractExpireDate(info.getContractExpireDate());
        return dto;
    }

    // ── HELPER: build UserLogin từ User entity ──────────────────────────────────
    private ResLoginDTO.UserLogin buildUserLogin(User user) {
        ResLoginDTO.UserLogin userLogin = new ResLoginDTO.UserLogin();
        userLogin.setId(user.getId());
        userLogin.setEmail(user.getEmail());
        userLogin.setName(user.getName());
        userLogin.setAvatar(user.getAvatar());
        userLogin.setRole(user.getRole());
        userLogin.setUserInfo(mapUserInfo(user)); // ← THÊM userInfo
        return userLogin;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ResLoginDTO> login(@Valid @RequestBody ReqLoginDTO loginDto, HttpServletRequest request) {
        String ip = extractClientIp(request);
        authLoginRateLimitService.checkAllowed(ip, loginDto.getUsername());

        // Nạp input gồm username/password vào Security
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                loginDto.getUsername(), loginDto.getPassword());

        // xác thực người dùng => cần viết hàm loadUserByUsername
        Authentication authentication;
        try {
            authentication = authenticationManagerBuilder.getObject()
                    .authenticate(authenticationToken);
        } catch (Exception e) {
            throw new IdInvalidException("Đăng nhập thất bại");
        }

        // set thông tin người dùng đăng nhập vào context (có thể sử dụng sau này)
        SecurityContextHolder.getContext().setAuthentication(authentication);

        ResLoginDTO res = new ResLoginDTO();
        User currentUserDB = this.userService.handleGetUserByUsername(loginDto.getUsername());

        if (currentUserDB == null || !currentUserDB.isActive()) {
            throw new IdInvalidException("Tài khoản đã bị vô hiệu hóa");
        }

        res.setUser(buildUserLogin(currentUserDB));
        // create access token
        String access_token = this.securityUtil.createAccessToken(authentication.getName(), res);
        res.setAccessToken(access_token);

        // create refresh token
        String refresh_token = this.securityUtil.createRefreshToken(loginDto.getUsername(), res);

        // save session
        String userAgent = request.getHeader("User-Agent");
        this.userSessionService.createSession(currentUserDB, refresh_token, userAgent, ip);

        // set cookies
        ResponseCookie resCookies = ResponseCookie
                .from("refresh_token", refresh_token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(refreshTokenExpiration)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, resCookies.toString())
                .body(res);
    }

    /**
     * Không tin tưởng "X-Forwarded-For"/"X-Real-IP" cho mục đích rate-limit vì hệ thống
     * chưa cấu hình trusted reverse-proxy — các header này do client tự đặt được, dùng
     * chúng làm khoá rate-limit sẽ cho phép né giới hạn số lần đăng nhập sai bằng cách
     * đổi header mỗi request.
     */
    private String extractClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    @GetMapping("/auth/account")
    @ApiMessage("fetch account")
    public ResponseEntity<ResLoginDTO.UserGetAccount> getAccount() {
        String email = SecurityUtil.getCurrentUserLogin().isPresent()
                ? SecurityUtil.getCurrentUserLogin().get()
                : "";

        User currentUserDB = this.userService.handleGetUserByUsername(email);

        ResLoginDTO.UserGetAccount userGetAccount = new ResLoginDTO.UserGetAccount();

        if (currentUserDB == null || !currentUserDB.isActive()) {
            throw new IdInvalidException("Tài khoản đã bị vô hiệu hóa");
        }

        userGetAccount.setUser(buildUserLogin(currentUserDB));

        return ResponseEntity.ok().body(userGetAccount);
    }

    @PostMapping("/auth/refresh")
    @ApiMessage("Get User by refresh token")
    public ResponseEntity<ResLoginDTO> getRefreshToken(
            @CookieValue(name = "refresh_token", defaultValue = "abc") String refresh_token)
            throws IdInvalidException {
        if (refresh_token.equals("abc")) {
            throw new IdInvalidException("Bạn không có refresh token ở cookie");
        }

        // check valid
        Jwt decodedToken = this.securityUtil.checkValidRefreshToken(refresh_token);
        String email = decodedToken.getSubject();

        // check session
        var sessionOpt = this.userSessionService.findByRefreshToken(refresh_token);
        if (sessionOpt.isEmpty()) {
            throw new IdInvalidException("Refresh token không hợp lệ hoặc đã bị đăng xuất khỏi thiết bị này.");
        }
        
        User currentUser = sessionOpt.get().getUser();

        if (currentUser == null || !currentUser.isActive()) {
            throw new IdInvalidException("User đã bị vô hiệu hóa");
        }

        // issue new token/set refresh token as cookies
        ResLoginDTO res = new ResLoginDTO();
        User currentUserDB = this.userService.handleGetUserByUsername(email);
        if (currentUserDB == null || !currentUserDB.isActive()) {
            throw new IdInvalidException("Tài khoản đã bị vô hiệu hóa");
        }

        // ✅ THÊM DÒNG NÀY
        res.setUser(buildUserLogin(currentUserDB));

        // create access token
        String access_token = this.securityUtil.createAccessToken(email, res);
        res.setAccessToken(access_token);

        // create refresh token
        String new_refresh_token = this.securityUtil.createRefreshToken(email, res);

        // update session token
        this.userSessionService.updateSessionToken(refresh_token, new_refresh_token);

        // set cookies
        ResponseCookie resCookies = ResponseCookie
                .from("refresh_token", new_refresh_token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(refreshTokenExpiration)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, resCookies.toString())
                .body(res);
    }

    @PostMapping("/auth/logout")
    @ApiMessage("Logout User")
    public ResponseEntity<Void> logout(@CookieValue(name = "refresh_token", defaultValue = "abc") String refresh_token) {
        // delete session
        if (StringUtils.hasText(refresh_token) && !refresh_token.equals("abc")) {
            this.userSessionService.deleteSession(refresh_token);
        }

        // remove refresh token cookie
        ResponseCookie deleteSpringCookie = ResponseCookie
                .from("refresh_token", null)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deleteSpringCookie.toString())
                .body(null);
    }

    @PostMapping("/auth/change-password")
    @ApiMessage("Change password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ReqChangePasswordDTO req) throws IdInvalidException {

        this.userService.changePassword(
                req.getOldPassword(),
                req.getNewPassword(),
                passwordEncoder);

        return ResponseEntity.ok().body(null);
    }
}
