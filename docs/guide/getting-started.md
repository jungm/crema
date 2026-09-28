# Getting started

A complete walk-through: add Crema to a WAR, declare an MCP Server, add Features and call them.

## Requirements

- Java 17 or newer
- A Jakarta EE 10 Web Profile (or newer) Runtime. Crema is tested on Apache TomEE 10, WildFly, Open Liberty and
  WebSphere Liberty.
- MicroProfile Config is optional, except for OAuth-protected MCP Servers.

## 1. Add the dependency

Crema isn't released yet; build it yourself (see [Building and testing](development.md)) to get
`0.1.0-SNAPSHOT` into your local Maven repository.

```xml
<dependency>
    <groupId>io.github.jungm</groupId>
    <artifactId>crema</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Use the default `compile` scope: Crema, `mcp-server-api` and Nimbus JOSE+JWT must end up in `WEB-INF/lib` of your
WAR. Compile with `-parameters` (`<maven.compiler.parameters>true</maven.compiler.parameters>`) so that Crema can use
Java parameter names as Argument names.

## 2. Declare an MCP Server

An MCP Server is declared by a subclass of `McpApplication`, a JAX-RS `Application`. Its `@ApplicationPath` is the
MCP Endpoint that clients connect to.

```java
import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;

@ApplicationPath("mcp")
@McpServerInfo(title = "Order Service", instructions = "Use these tools to look up and place orders.")
public class OrderMcp extends McpApplication {
}
```

The subclass must stay empty: Crema fails the deployment if it declares any methods. It coexists with your own JAX-RS
`Application`s, and your providers (exception mappers, JSON-B configuration, filters, message body readers and
writers) don't touch MCP traffic: Crema answers MCP requests inside its own `@PreMatching` request filter. The one
exception is TomEE, which applies the providers your scanning `Application` discovers to every `Application` of the
WAR, so an application `@PreMatching` request filter that TomEE orders before Crema's runs for MCP requests too.

## 3. Add Features to a CDI bean

Any CDI bean with a bean-defining annotation (`@ApplicationScoped`, `@RequestScoped`, `@Dependent`, ...) can carry
Feature Methods. Crema gets the instance from CDI for each call, so `@Inject`, interceptors and transactions work as
usual.

```java
import java.math.BigDecimal;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

@ApplicationScoped
public class OrderFeatures {

    public record Item(String sku, int quantity) {}
    public record Order(String id, String customer, List<Item> items, BigDecimal total) {}

    @Inject
    OrderService orders;

    @Tool(description = "Places an order for a customer")
    public Order placeOrder(@ToolArg(description = "Customer number") String customer,
                            @ToolArg(description = "What to order") List<Item> items) {
        return orders.place(customer, items);
    }

    @Resource(uri = "orders://recent", description = "The 20 most recent orders", mimeType = "application/json")
    public List<Order> recentOrders() {
        return orders.recent(20);
    }

    @ResourceTemplate(uriTemplate = "orders://{id}", name = "order", description = "One order")
    public Order order(String id) {
        return orders.find(id);
    }

    @Prompt(description = "Summarizes a customer's order history")
    public String orderSummary(@PromptArg(description = "Customer number") String customer) {
        return "Summarize the order history of customer " + customer + ". Use the order tools and resources.";
    }
}
```

Crema generates the JSON Schema for `placeOrder`'s Arguments from the Java types, binds the client's JSON to them
(the `Item` records through JSON-B), and returns the `Order` as JSON text.

## 4. Try it

Deploy the WAR. The MCP Endpoint is `http://<host>:<port>/<context-root>/mcp`. Point an MCP Client that supports
revision `2026-07-28` over Streamable HTTP at that URL, or talk to it with `curl`. MCP `2026-07-28` is stateless:
there is no handshake, and every request carries the protocol version and client capabilities in its `_meta`, plus a
few headers that repeat what's in the body:

```sh
curl -s http://localhost:8080/shop/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/list' \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {"_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}}}}'
```

To call a Tool, also send `Mcp-Name` with the Tool's name (for `resources/read`, the resource URI; for
`prompts/get`, the Prompt's name):

```sh
curl -s http://localhost:8080/shop/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: placeOrder' \
  -d '{"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
        "name": "placeOrder",
        "arguments": {"customer": "C-42", "items": [{"sku": "coffee-beans", "quantity": 2}]},
        "_meta": {
          "io.modelcontextprotocol/protocolVersion": "2026-07-28",
          "io.modelcontextprotocol/clientCapabilities": {}}}}'
```

`server/discover` describes the MCP Server and its capabilities. MCP `2026-07-28` has no server-to-client stream, so
`GET` and `DELETE` on the MCP Endpoint answer `405`. That comes after the `Origin` check and, on a protected MCP
Server, after authentication, so an unauthenticated `GET` there gets `401`.
