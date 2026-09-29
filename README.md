<p>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/crema-logo-dark.svg">
    <img src="docs/images/crema-logo.svg" alt="Crema" width="340" align="left">
  </picture>
</p>

<h3><em>Where your enterprise beans meet AI.</em></h3>

An MCP server implementation for Jakarta EE.

<br clear="left">

Crema turns methods of your CDI beans into [Model Context Protocol](https://modelcontextprotocol.io) Tools, Resources
and Prompts that AI clients can discover and call. It is a plain library in your WAR, implements the
[`org.mcpjava:mcp-server-api`](https://github.com/mcp-java/java-mcp-annotations) annotations and MCP `2026-07-28`
(Streamable HTTP), and runs on any Jakarta EE 10 Web Profile Runtime. It is tested on TomEE, WildFly, Open Liberty and
Payara/GlassFish.

## Example

```xml
<dependency>
    <groupId>io.github.jungm</groupId>
    <artifactId>crema</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Declare an MCP Server:

```java
@ApplicationPath("mcp")
@McpServerInfo(title = "Order Service")
public class OrderMcp extends McpApplication {
}
```

Then annotate methods of any CDI bean:

```java
@ApplicationScoped
public class OrderFeatures {

    @Inject
    OrderService orders;

    @Tool(description = "Places an order for a customer")
    public Order placeOrder(@ToolArg(description = "Customer number") String customer, List<Item> items) {
        return orders.place(customer, items);
    }

    @ResourceTemplate(uriTemplate = "orders://{id}", name = "order", description = "One order")
    public Order order(String id) {
        return orders.find(id);
    }

    @Prompt(description = "Summarizes a customer's order history")
    public String orderSummary(String customer) {
        return "Summarize the order history of customer " + customer + ".";
    }
}
```

Deploy the WAR and point an MCP Client at `http://<host>:<port>/<context-root>/mcp`. To protect the MCP Server
with OAuth, add `@RolesAllowed` and tell Crema about your Authorization Server:

```java
@ApplicationPath("mcp")
@RolesAllowed("user")
public class OrderMcp extends McpApplication {
}
```

```properties
crema.default-server.issuer=https://keycloak.example.com/realms/shop
crema.default-server.resource=https://shop.example.com/shop/mcp
```

## Documentation

- [Getting started](docs/guide/getting-started.md): requirements, setup and trying it with `curl`
- [Programming model](docs/guide/programming-model.md): Arguments, Injected Parameters, return types, multiple MCP
  Servers, deployment-time validation
- [Configuration](docs/guide/configuration.md): all MicroProfile Config keys
- [Security](docs/guide/security.md): Origin check and roles
- [Authentication](docs/guide/authentication.md): protecting MCP Servers with OAuth, Basic authentication or your own
  Authenticator
- [Limitations](docs/guide/limitations.md): what isn't supported yet
- [Building and testing](docs/guide/development.md): unit tests, integration tests on four Runtimes, conformance suite

## License

[Apache License 2.0](LICENSE)
