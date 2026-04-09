import org.junit.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.Assert.*;

public class testBcrypt {
    @Test
    public void testEncode() {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        String rawPassword = "123456";

        String encodePassword = passwordEncoder.encode(rawPassword);
        System.out.println(encodePassword);

        assertNotNull(encodePassword);
        assertNotEquals(rawPassword, encodePassword);

        assertTrue(passwordEncoder.matches(rawPassword, encodePassword));
    }
}
