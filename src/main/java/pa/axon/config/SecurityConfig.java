package pa.axon.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AccountType;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;
import pa.axon.security.LoginFailureHandler;
import pa.axon.security.PatientUserDetailsService;
import pa.axon.security.ProfessionalUserDetailsService;

/**
 * Dos cadenas separadas: /pro/** para el profesional y todo lo demás para el paciente. Cada una
 * tiene su propio proveedor de autenticación, así una cédula nunca abre la puerta del profesional
 * ni al revés (RS-02, RS-03). Lo que no se lista explícitamente se deniega.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Los UserDetailsService no son beans: con dos, Spring Security avisa y no elige ninguno.
    @Bean
    AuthenticationProvider patientAuthProvider(PatientRepository patients, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(new PatientUserDetailsService(patients));
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    AuthenticationProvider professionalAuthProvider(ProfessionalRepository professionals, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(new ProfessionalUserDetailsService(professionals));
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    @Order(1)
    SecurityFilterChain professionalChain(HttpSecurity http,
            @Qualifier("professionalAuthProvider") AuthenticationProvider provider,
            LoginAttemptService attempts) throws Exception {
        http
                .securityMatcher("/pro", "/pro/**")
                .authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/pro", "/pro/login").permitAll()
                        .anyRequest().hasRole(AxonUser.PROFESIONAL))
                .formLogin(form -> form
                        .loginPage("/pro")
                        .loginProcessingUrl("/pro/login")
                        .usernameParameter("idoneidad")
                        .passwordParameter("clave")
                        .defaultSuccessUrl("/pro/canjear", true)
                        .failureHandler(new LoginFailureHandler(AccountType.PROFESIONAL, "idoneidad", "/pro?error", attempts)))
                .logout(logout -> logout
                        .logoutUrl("/pro/logout")
                        .logoutSuccessUrl("/pro"));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain patientChain(HttpSecurity http,
            @Qualifier("patientAuthProvider") AuthenticationProvider provider,
            LoginAttemptService attempts) throws Exception {
        http
                .authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/salud", "/error", "/css/**", "/js/**", "/img/**", "/fonts/**").permitAll()
                        // Público, salvo para un profesional con sesión: no crea cuentas de paciente (HU-13).
                        .requestMatchers("/registro").not().hasRole(AxonUser.PROFESIONAL)
                        .requestMatchers("/cartilla/**", "/compartir/**", "/permisos/**",
                                "/emergencia/**", "/actividad/**", "/documento/**").hasRole(AxonUser.PACIENTE)
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginPage("/")
                        .loginProcessingUrl("/login")
                        .usernameParameter("cedula")
                        .passwordParameter("clave")
                        .defaultSuccessUrl("/cartilla", true)
                        .failureHandler(new LoginFailureHandler(AccountType.PACIENTE, "cedula", "/?error", attempts)))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/"));
        return http.build();
    }
}
