import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class testBcrypt {
    @Test
    void testEncode() {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        String rawPassword = "123456";

        String encodePassword = passwordEncoder.encode(rawPassword);

        assertNotNull(encodePassword);
        assertNotEquals(rawPassword, encodePassword);
        assertTrue(passwordEncoder.matches(rawPassword, encodePassword));
    }
}
