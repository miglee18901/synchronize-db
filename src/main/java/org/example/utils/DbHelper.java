package org.example.utils;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.io.File;

public class DbHelper {
    public static SessionFactory buildSessionFactory(File configFile) {
        if (configFile == null || !configFile.exists()) {
            throw new IllegalArgumentException("Hibernate configuration file not found: " + (configFile != null ? configFile.getAbsolutePath() : "null"));
        }
        Configuration cfg = new Configuration().configure(configFile);
        return cfg.buildSessionFactory();
    }
}
