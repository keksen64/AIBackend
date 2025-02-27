import okhttp3.*;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class EmbeddingEndpoint implements HttpHandler {
    private static final String PYTHON_SERVER_URL = "http://127.0.0.1:5000/embedding";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, 0);
            return;
        }

        String document = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println("📩 Получен текст для эмбеддинга: " + document);

        try {
            String embedding = generateEmbedding("query: " + document);

            exchange.sendResponseHeaders(200, embedding.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(embedding.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, 0);
        }
    }

    private String generateEmbedding(String text) throws IOException {
        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), text);
        Request request = new Request.Builder().url(PYTHON_SERVER_URL).post(body).build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            throw new IOException("Ошибка запроса к Python-серверу: " + response);
        }

        return response.body().string();
    }
}
