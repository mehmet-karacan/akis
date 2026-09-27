package tr.com.innova.akis.security;

import org.springframework.stereotype.Component;

import tr.com.innova.akis.metadata.ApiException;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Component
public final class PasswordPolicy {

    public void validate(String password) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < 15 || length > 128) {
            throw new ApiException(
                    UNPROCESSABLE_CONTENT,
                    "PASSWORD_POLICY_FAILED",
                    "Parola 15-128 karakter olmalıdır.");
        }
    }
}

