package com.task09;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.stream.Collectors;

public class OpenMeteoClient {

    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

    public String getWeatherForecast() throws Exception {
        String urlString = BASE_URL
                + "?latitude=50.4375"
                + "&longitude=30.5"
                + "&current=temperature_2m,wind_speed_10m"
                + "&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m"
                + "&timezone=Europe%2FKiev";

        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            return reader.lines().collect(Collectors.joining());
        } finally {
            connection.disconnect();
        }
    }
}