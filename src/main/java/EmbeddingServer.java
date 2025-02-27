import okhttp3.*;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class EmbeddingServer {

    private static final String PYTHON_SERVER_URL = "http://127.0.0.1:5000/embedding";
    private static final String DATABASE_URL = "jdbc:postgresql://localhost:5432/AIDB";
    private static final String DATABASE_USER = "postgres";
    private static final String DATABASE_PASSWORD = "admin";
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/document/add", new EmbeddingHandler());
        server.createContext("/request/embedding", new EmbeddingEndpoint());

        server.setExecutor(null);
        server.start();
        System.out.println("🚀 HTTP сервер запущен на http://localhost:" + PORT);
    }

    static class EmbeddingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            String document = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("📩 Получен текст: \n" + document);

            // Разделяем документ по строкам
            String[] lines = document.split("\n", 4); // Максимум 4 элемента

            if (lines.length < 4) {
                System.err.println("❌ Ошибка: В документе должно быть минимум 4 строки!");
                exchange.sendResponseHeaders(400, 0);
                return;
            }

            String docName = lines[0].trim();
            String actualDate = lines[1].trim();
            String state = lines[2].trim();
            String mainText = lines[3].trim(); // Остальной текст идёт в эмбеддинг

            try {
                // Отправляем только основной текст на эмбеддинг
                String embedding = generateEmbedding(mainText);

                // Сохраняем всё в БД
                saveEmbeddingToDatabase(docName, actualDate, state, mainText, embedding);

                String response = "✅ Эмбеддинг сохранён в БД!";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                String errorResponse = "❌ Ошибка: " + e.getMessage();
                byte[] errorBytes = errorResponse.getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(500, errorBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorBytes);
                }
            }
        }

        private String generateEmbedding(String text) throws IOException {
            OkHttpClient client = new OkHttpClient();
            RequestBody body = RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), text);

            Request request = new Request.Builder()
                    .url(PYTHON_SERVER_URL)
                    .post(body)
                    .build();

            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                throw new IOException("Ошибка запроса к Python-серверу: " + response);
            }

            String responseBody = response.body().string();
            response.close();

            // Извлекаем вектор эмбеддинга из JSON
            int startIndex = responseBody.indexOf("[");
            int endIndex = responseBody.lastIndexOf("]") + 1;

            if (startIndex == -1 || endIndex == 0) {
                throw new IOException("Некорректный JSON-ответ: " + responseBody);
            }

            return responseBody.substring(startIndex, endIndex);
        }

        private void saveEmbeddingToDatabase(String docName, String actualDate, String state, String text, String embedding) throws SQLException {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(DATABASE_URL);
            dataSource.setUser(DATABASE_USER);
            dataSource.setPassword(DATABASE_PASSWORD);

            try (Connection connection = dataSource.getConnection()) {
                String sql = "INSERT INTO \"AISTORAGE\".documents (docName, actualDate, state, text, embedding) VALUES (?, ?, ?, ?, ?::vector)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, docName);
                    statement.setString(2, actualDate);
                    statement.setString(3, state);
                    statement.setString(4, text);
                    statement.setString(5, embedding);
                    statement.executeUpdate();
                }
            }
            System.out.println("✅ Эмбеддинг сохранён в БД!");
        }
    }
}
