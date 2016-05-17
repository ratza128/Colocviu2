package home.practicaltest;

import android.content.Context;
import android.provider.SyncStateContract;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.http.NameValuePair;
import org.apache.http.client.methods.HttpPost;
import org.json.JSONException;
import org.w3c.dom.Text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    public EditText adresaClient,port,city;
    public Spinner info;
    public EditText portServer;
    public TextView ServerReply;
    public Button buttonClient,buttonServer;
    public ServerThread serverThread;
    public TextView weatherForecastTextView;
    private ConnectButtonClickListener connectButton = new ConnectButtonClickListener();
    private GetWeatherForecastButtonClickListener getWeather = new GetWeatherForecastButtonClickListener();

    private class CommunicationThread extends Thread {

        private Socket socket;
        private Random random = new Random();
        private Context ctx;
        private String expectedWordPrefix = new String();

        public CommunicationThread(Context ctx,Socket socket) {
            if (socket != null) {
                this.socket = socket;
                this.ctx = ctx;
                Log.d("Debug", "[SERVER] Created communication thread with: "+socket.getInetAddress());
            }
        }

        @Override
        public void run() {
            if (socket != null) {
                try {
                    BufferedReader bufferedReader = Utilities.getReader(socket);
                    PrintWriter    printWriter    = Utilities.getWriter(socket);
                    if (bufferedReader != null && printWriter != null) {
                        Log.d("Debug", "[COMMUNICATION THREAD] Waiting for parameters from client (city / information type)!");
                        String city            = bufferedReader.readLine();
                        String informationType = bufferedReader.readLine();
                        HashMap<String, WeatherForecastInformation> data = serverThread.getData();
                        WeatherForecastInformation weatherForecastInformation = null;
                        if (city != null && !city.isEmpty() && informationType != null && !informationType.isEmpty()) {
                            if (data.containsKey(city)) {
                                Log.d("Debug", "[COMMUNICATION THREAD] Getting the information from the cache...");
                                weatherForecastInformation = data.get(city);
                            } else {
                                Log.d("Debug", "[COMMUNICATION THREAD] Getting the information from the webservice...");
                                HttpClient httpClient = new DefaultHttpClient();
                                HttpPost httpPost = new HttpPost(SyncStateContract.Constants.WEB_SERVICE_ADDRESS);//??
                                List<NameValuePair> params = new ArrayList<NameValuePair>();
                                params.add(new BasicNameValuePair(SyncStateContract.Constants.QUERY_ATTRIBUTE, city));//??
                                UrlEncodedFormEntity urlEncodedFormEntity = new UrlEncodedFormEntity(params, HTTP.UTF_8);
                                httpPost.setEntity(urlEncodedFormEntity);
                                ResponseHandler<String> responseHandler = new BasicResponseHandler();
                                String pageSourceCode = httpClient.execute(httpPost, responseHandler);
                                if (pageSourceCode != null) {
                                    Document document = Jsoup.parse(pageSourceCode);
                                    Element element = document.child(0);
                                    Elements scripts = element.getElementsByTag(Constants.SCRIPT_TAG);
                                    for (Element script: scripts) {
                                        String scriptData = script.data();
                                        if (scriptData.contains(Constants.SEARCH_KEY)) {
                                            int position = scriptData.indexOf(Constants.SEARCH_KEY) + Constants.SEARCH_KEY.length();
                                            scriptData = scriptData.substring(position);
                                            JSONObject content = new JSONObject(scriptData);
                                            JSONObject currentObservation = content.getJSONObject(Constants.CURRENT_OBSERVATION);
                                            String temperature = currentObservation.getString(Constants.TEMPERATURE);
                                            String windSpeed = currentObservation.getString(Constants.WIND_SPEED);
                                            String condition = currentObservation.getString(Constants.CONDITION);
                                            String pressure = currentObservation.getString(Constants.PRESSURE);
                                            String humidity = currentObservation.getString(Constants.HUMIDITY);
                                            weatherForecastInformation = new WeatherForecastInformation(
                                                    temperature,
                                                    windSpeed,
                                                    condition,
                                                    pressure,
                                                    humidity
                                            );
                                            serverThread.setData(city, weatherForecastInformation);
                                            break;
                                        }
                                    }
                                } else {
                                    Log.e("Error", "[COMMUNICATION THREAD] Error getting the information from the webservice!");
                                }
                            }
                            if (weatherForecastInformation != null) {
                                String result = null;
                                if (Constants.ALL.equals(informationType)) {
                                    result = weatherForecastInformation.toString();
                                } else if (Constants.TEMPERATURE.equals(informationType)) {
                                    result = weatherForecastInformation.getTemp();
                                } else if (Constants.WIND_SPEED.equals(informationType)) {
                                    result = weatherForecastInformation.getWind();
                                } else if (Constants.CONDITION.equals(informationType)) {
                                    result = weatherForecastInformation.getStare();
                                } else if (Constants.HUMIDITY.equals(informationType)) {
                                    result = weatherForecastInformation.getUmiditate();
                                } else if (Constants.PRESSURE.equals(informationType)) {
                                    result = weatherForecastInformation.getPressure();
                                } else {
                                    result = "Wrong information type (all / temperature / wind_speed / condition / humidity / pressure)!";
                                }
                                printWriter.println(result);
                                printWriter.flush();
                            } else {
                                Log.e("Error", "[COMMUNICATION THREAD] Weather Forecast information is null!");
                            }
                        } else {
                            Log.e("Error", "[COMMUNICATION THREAD] Error receiving parameters from client (city / information type)!");
                        }
                    } else {
                        Log.e("Error", "[COMMUNICATION THREAD] BufferedReader / PrintWriter are null!");
                    }
                    socket.close();
                } catch (IOException ioException) {
                    Log.e("Error", "[COMMUNICATION THREAD] An exception has occurred: " + ioException.getMessage());
                        ioException.printStackTrace();
                } catch (JSONException jsonException) {
                    Log.e("Error", "[COMMUNICATION THREAD] An exception has occurred: " + jsonException.getMessage());
                        jsonException.printStackTrace();
                }
            } else {
                Log.e("Error", "[COMMUNICATION THREAD] Socket is null!");
            }
        }
    }

    private class ServerThread extends Thread {

        HashMap<String,WeatherForecastInformation> data = new HashMap<String, WeatherForecastInformation>();

        boolean isRunning = true;
        private ServerSocket serverSocket;

        public ServerThread(int port) {
            try {
                serverSocket = new ServerSocket(port);
                isRunning = true;
            } catch (IOException ioException) {
                Log.e("error", "An exception has occurred:" + ioException.getMessage());
                    ioException.printStackTrace();
            }
        }
        private ServerSocket getServerSocket(){
            return this.serverSocket;
        }
        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Log.d("Debug", "[SERVER] Waiting for a connection...");
                    Socket socket = serverSocket.accept();
                    Log.d("Debug", "[SERVER] A connection request was received from " + socket.getInetAddress() + ":" + socket.getLocalPort());
                    CommunicationThread communicationThread = new CommunicationThread(getApplicationContext(), socket);
                    communicationThread.start();
                }
            }  catch (IOException ioException) {
                Log.e("error", "An exception has occurred: " + ioException.getMessage());
                    ioException.printStackTrace();
            }
        }

        public synchronized void setData(String city, WeatherForecastInformation weatherForecastInformation) {
            this.data.put(city, weatherForecastInformation);
        }

        public synchronized HashMap<String, WeatherForecastInformation> getData() {
            return data;
        }

        public void stopThread() {
            if (serverSocket != null) {
                interrupt();
                try {
                    if (serverSocket != null) {
                        serverSocket.close();
                    }
                } catch (IOException ioException) {
                    Log.e("Error", "An exception has occurred: " + ioException.getMessage());
                        ioException.printStackTrace();
                }
            }
        }
    }

    private class ClientThread extends Thread {

        private Socket socket;
        private String address,city,info;
        private int port;
        private WeatherForecastInformation weather;

        public ClientThread(String clientAddress,int clientPort,String city,String info,WeatherForecastInformation weather){
            this.address = clientAddress;
            this.port = clientPort;
            this.city = city;
            this.info = info;
            this.weather = weather;
        }

        @Override
        public void run() {
            try {
                socket = new Socket(address, port);
                if (socket == null) {
                    Log.e("Error", "[CLIENT THREAD] Could not create socket!");
                    return;
                }
                BufferedReader bufferedReader = Utilities.getReader(socket);
                PrintWriter    printWriter    = Utilities.getWriter(socket);
                if (bufferedReader != null && printWriter != null) {
                    printWriter.println(city);
                    printWriter.flush();
                    printWriter.println(info);
                    printWriter.flush();
                    String weatherInformation;
                    while ((weatherInformation = bufferedReader.readLine()) != null) {
                        final String finalizedWeatherInformation = weatherInformation;
                        weatherForecastTextView.post(new Runnable() {
                            @Override
                            public void run() {
                                weatherForecastTextView.append(finalizedWeatherInformation + "\n");
                            }
                        });
                    }
                } else {
                    Log.e("Error", "[CLIENT THREAD] BufferedReader / PrintWriter are null!");
                }
                socket.close();
            } catch (IOException ioException) {
                Log.e("Error", "[CLIENT THREAD] An exception has occurred: " + ioException.getMessage());
                    ioException.printStackTrace();

            }
        }
    }

    private class WeatherForecastInformation {
        private String temperatura,viteza,stare_generala,presiune,umiditate;

        public void setTemp(String temp){
            this.temperatura = temp;
        }
        public String getTemp(){
            return this.temperatura;
        }

        public void setWind(String wind){
            this.viteza = wind;
        }
        public String getWind(){
            return this.viteza;
        }

        public void setStare(String stare){
            this.stare_generala = stare;
        }
        public String getStare(){
            return this.stare_generala;
        }

        public void setPressure(String pressure){
            this.presiune = pressure;
        }
        public String getPressure(){
            return this.presiune;
        }

        public void setUmiditate(String um){
            this.umiditate = um;
        }
        public String getUmiditate(){
            return this.umiditate;
        }

    }

    private class ConnectButtonClickListener implements Button.OnClickListener {
        @Override
        public void onClick(View view) {
            String serverPort = portServer.getText().toString();
            if (serverPort == null || serverPort.isEmpty()) {
                Toast.makeText(
                        getApplicationContext(),
                        "Server port should be filled!",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            serverThread = new ServerThread(Integer.parseInt(serverPort));
            if (serverThread.getServerSocket() != null) {
                serverThread.start();
            } else {
                Log.e("error", "[MAIN ACTIVITY] Could not creat server thread!");
            }
        }
    }


    private class GetWeatherForecastButtonClickListener implements Button.OnClickListener {
        @Override
        public void onClick(View view) {
            String clientAddress = adresaClient.getText().toString();
            String clientPort    = port.getText().toString();
            if (clientAddress == null || clientAddress.isEmpty() ||
                    clientPort == null || clientPort.isEmpty()) {
                Toast.makeText(
                        getApplicationContext(),
                        "Client connection parameters should be filled!",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            if (serverThread == null || !serverThread.isAlive()) {
                Log.e("Error", "[MAIN ACTIVITY] There is no server to connect to!");
                return;
            }
            String cityy = city.getText().toString();
            String informationType = informationTypeSpinner.getSelectedItem().toString();
            if (cityy == null || cityy.isEmpty() ||
                    informationType == null || informationType.isEmpty()) {
                Toast.makeText(
                        getApplicationContext(),
                        "Parameters from client (city / information type) should be filled!",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            weatherForecastTextView.setText("");
            clientThread = new ClientThread(
                    clientAddress,
                    Integer.parseInt(clientPort),
                    city,
                    informationType,
                    weatherForecastTextView);
            clientThread.start();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        portServer = (EditText)findViewById(R.id.server_port_edit_text);
        adresaClient = (EditText) findViewById(R.id.client_address_edit_text);
        port = (EditText) findViewById(R.id.client_port_edit_text);
        city = (EditText) findViewById(R.id.city_edit_text);
        info = (Spinner) findViewById(R.id.information_type_spinner);
        weatherForecastTextView = (TextView) findViewById(R.id.weather_forecast_text_view);

        buttonServer = (Button) findViewById(R.id.connect_button);
        buttonServer.setOnClickListener(connectButton);

        buttonClient = (Button) findViewById(R.id.get_weather_forecast_button);
        buttonClient.setOnClickListener(getWeather);
    }
    @Override
    protected void onDestroy() {
        if (serverThread != null) {
            serverThread.stopThread();
        }
        super.onDestroy();
    }
}
