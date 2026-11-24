package com.atlas.payment.provider.paypal;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atlas.payment.paypal.payouts")
public class PayPalPayoutsProperties {
    private boolean enabled;
    private Set<String> supportedCountries = new LinkedHashSet<>(Set.of("US"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getSupportedCountries() {
        return Set.copyOf(supportedCountries);
    }

    public void setSupportedCountries(Set<String> supportedCountries) {
        this.supportedCountries = supportedCountries == null
                ? new LinkedHashSet<>(Set.of("US")) : new LinkedHashSet<>(supportedCountries);
    }
}
