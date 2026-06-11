package com.task09.layer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.stream.Collectors;

public class OpenMeteoClient {

    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

    public String getWeatherForecast() throws Exception {
        String urlString = BASE_URL + "?latitude=50.4375&longitude=30.5"
                + "&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m"
                + "&current=temperature_2m,wind_speed_10m"
                + "&timezone=Europe%2FKiev";

        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                return reader.lines().collect(Collectors.joining());
            }
        } else {
            throw new RuntimeException("Failed to get weather data. HTTP code: " + responseCode);
        }
    }
}
