package io.github.jungm.crema.it.security.app;

import jakarta.annotation.security.DeclareRoles;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** The application's web UI, protected by the Jakarta Security OpenID Connect mechanism of {@link OidcConfig}. */
@WebServlet("/ui")
@ServletSecurity(@HttpConstraint(rolesAllowed = "user"))
@DeclareRoles("user")
public class UiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        resp.getWriter().println("ui " + req.getUserPrincipal().getName());
    }
}
