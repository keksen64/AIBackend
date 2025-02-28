import org.json.JSONArray;
import org.json.JSONObject;
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
import java.util.ArrayList;
import java.util.List;

public class EmbeddingServer {

    private static final String PYTHON_SERVER_URL = "http://127.0.0.1:5000";
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

            String[] lines = document.split("\n", 4);
            if (lines.length < 4) {
                System.err.println("❌ Ошибка: В документе должно быть минимум 4 строки!");
                exchange.sendResponseHeaders(400, 0);
                return;
            }

            String docName = lines[0].trim();
            String actualDate = lines[1].trim();
            String state = lines[2].trim();
            String mainText = lines[3].trim();

            try {
                // 🔹 1. Отправляем текст в Python на нарезку и получение токенов
                JSONObject splitResponse = splitTextInPython(mainText);
                JSONArray chunksArray = splitResponse.getJSONArray("chunks");
                JSONArray tokenCountsArray = splitResponse.getJSONArray("token_counts");

                int splitCount = chunksArray.length();

                for (int i = 0; i < splitCount; i++) {
                    String chunkText = "passage: " + chunksArray.getString(i);
                    int tokenCount = tokenCountsArray.getInt(i);  // Количество токенов в этом куске

                    // 🔹 2. Отправляем каждую часть на эмбеддинг
                    String embedding = generateEmbedding(chunkText);

                    // 🔹 3. Сохраняем в БД
                    saveEmbeddingToDatabase(docName, actualDate, state, chunkText, embedding, splitCount, i + 1, tokenCount);
                }

                String response = "✅ Эмбеддинги сохранены в БД!";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            }
        }

        private JSONObject splitTextInPython(String text) throws IOException {
            OkHttpClient client = new OkHttpClient();
            RequestBody body = RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), text);
            Request request = new Request.Builder().url(PYTHON_SERVER_URL + "/split_text").post(body).build();
            Response response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                throw new IOException("Ошибка запроса к Python-серверу: " + response);
            }

            String responseBody = response.body().string();
            response.close();

            return new JSONObject(responseBody);  // Возвращаем JSON с chunks + token_counts
        }

        private String generateEmbedding(String text) throws IOException {
            OkHttpClient client = new OkHttpClient();
            RequestBody body = RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), text);
            Request request = new Request.Builder().url(PYTHON_SERVER_URL + "/embedding").post(body).build();
            Response response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                throw new IOException("Ошибка запроса к Python-серверу: " + response);
            }

            String responseBody = response.body().string();
            response.close();

            int startIndex = responseBody.indexOf("[");
            int endIndex = responseBody.lastIndexOf("]") + 1;
            if (startIndex == -1 || endIndex == 0) {
                throw new IOException("Некорректный JSON-ответ: " + responseBody);
            }
            return responseBody.substring(startIndex, endIndex);
        }

        private void saveEmbeddingToDatabase(String docName, String actualDate, String state, String text, String embedding, int splitCount, int splitNumber, int tokenCount) throws SQLException {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(DATABASE_URL);
            dataSource.setUser(DATABASE_USER);
            dataSource.setPassword(DATABASE_PASSWORD);

            try (Connection connection = dataSource.getConnection()) {
                String sql = "INSERT INTO \"AISTORAGE\".documents (docname, actualdate, state, text, embedding, splitCount, splitNumber, token_count) VALUES (?, ?, ?, ?, ?::vector, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, docName);
                    statement.setString(2, actualDate);
                    statement.setString(3, state);
                    statement.setString(4, text);
                    statement.setString(5, embedding);
                    statement.setInt(6, splitCount);
                    statement.setInt(7, splitNumber);
                    statement.setInt(8, tokenCount);
                    statement.executeUpdate();
                }
            }
            System.out.println("✅ Эмбеддинг сохранён в БД!");
        }
    }
}
