# Crema

A portable implementation of the `org.mcpjava` MCP server annotations for Jakarta EE runtimes, exposing annotated application code to MCP clients over Streamable HTTP.

## Language

### Hosting

**Runtime**:
The Jakarta EE application server the application is deployed to (TomEE, Open Liberty, WebSphere Liberty, WildFly).
_Avoid_: server, container, app server

**MCP Server**:
A named set of Features exposed to MCP clients as one MCP endpoint, identified by the name given in `@McpServer` (or the default name).
_Avoid_: server configuration, server, endpoint

**MCP Endpoint**:
The URL at which exactly one MCP Server is reachable within an application.
_Avoid_: path, route, URL

**Resource Identifier**:
The canonical URL that identifies one MCP Server to the Authorization Server; access tokens are only valid for the MCP Server whose Resource Identifier is in their audience.
_Avoid_: audience, canonical URI, resource URL

**Authorization Server**:
The OAuth identity provider that issues access tokens for MCP Servers.
_Avoid_: IdP, OIDC provider, Keycloak

**Caller**:
The authenticated principal on whose behalf an MCP Client sends a request, or anonymous.
_Avoid_: user, subject, client

**MCP Client**:
The AI application that connects to an MCP Server and calls its Features on behalf of a model or user.
_Avoid_: agent, consumer, caller

### Features

**Feature**:
A capability an MCP Server offers to clients: a Tool, Resource, Resource Template or Prompt.
_Avoid_: capability, handler, operation

**Feature Method**:
An application method annotated with `@Tool`, `@Resource`, `@ResourceTemplate` or `@Prompt` that implements a Feature.
_Avoid_: handler, endpoint method

**Completion Method**:
An application method annotated with `@CompletePrompt` or `@CompleteResourceTemplate` that suggests values for one argument of a Prompt or Resource Template.
_Avoid_: completer, autocomplete

**Argument**:
A named, client-supplied input of a Tool, Prompt or Resource Template, bound to a Feature Method parameter.
_Avoid_: param, input

**Injected Parameter**:
A Feature Method parameter supplied by the implementation rather than by the client, such as `McpRequest`, `Cancellation` or `Progress`.
_Avoid_: context parameter, special parameter
