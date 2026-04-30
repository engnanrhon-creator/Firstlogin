package com.example.firstlogin;

public class Users {

    // ===== USER FIELDS =====
    private String fullname;
    private String emailaddress;
    private String password;

    // Model used by local SQLite helper for sign-up/login caching.
    public Users(String fullname, String emailaddress, String password) {
        this.fullname = fullname;
        this.emailaddress = emailaddress;
        this.password = password;
    }

    // ===== GETTERS =====
    public String getFullname() {
        return fullname;
    }

    public String getEmailaddress() {
        return emailaddress;
    }

    public String getPassword() {
        return password;
    }

    // ===== SETTERS =====
    public void setFullname(String fullname) {
        this.fullname = fullname;
    }

    public void setEmailaddress(String emailaddress) {
        this.emailaddress = emailaddress;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
