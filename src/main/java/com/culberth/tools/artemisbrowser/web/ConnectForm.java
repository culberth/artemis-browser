package com.culberth.tools.artemisbrowser.web;

/** Backing object for the connect form. Mutable because Thymeleaf binds onto it. */
public class ConnectForm {

    private String host = "localhost";
    private String port = "61616";
    private String username = "";
    private String password = "";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
