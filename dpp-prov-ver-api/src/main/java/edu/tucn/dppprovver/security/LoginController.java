package edu.tucn.dppprovver.security;

import edu.tucn.dppprovver.exceptions.UnauthorizedException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController // creates an instance of the current class
@RequestMapping("/login") // maps the requests starting with '/login' to this controller
@Tag(name = "Login")
public class LoginController {

    @PostMapping
    public JwtTokenDTO login(CredentialsDTO credentialsDTO) {
        if ("user1".equals(credentialsDTO.getUsername()) && "pwd1".equals(credentialsDTO.getPassword())) {
            JwtTokenDTO jwtTokenDTO = new JwtTokenDTO();
            jwtTokenDTO.setToken(JwtUtil.generateToken(credentialsDTO.getUsername()));
            return jwtTokenDTO;
        } else {
            throw new UnauthorizedException("Bad credentials");
        }
    }
}
