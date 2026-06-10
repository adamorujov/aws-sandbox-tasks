package com.task09;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class OpenMeteoClient {

    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

    public String getWeatherForecast() {
        return getWeatherForecast(50.4375, 30.5);
    }

    public String getWeatherForecast(double latitude, double longitude) {
        try {
            String urlString = BASE_URL
                    + "?latitude=" + latitude
                    + "&longitude=" + longitude
                    + "&current=temperature_2m,wind_speed_10m"
                    + "&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m"
                    + "&timezone=Europe%2FKiev";

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            return response.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}