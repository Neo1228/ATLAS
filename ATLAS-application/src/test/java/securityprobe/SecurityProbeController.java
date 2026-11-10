package securityprobe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SecurityProbeController {
    @GetMapping("/api/private")
    public String privateRoute() {
        return "authenticated";
    }

    @GetMapping("/api/products")
    public String publicCatalogRoute() {
        return "public";
    }
}
