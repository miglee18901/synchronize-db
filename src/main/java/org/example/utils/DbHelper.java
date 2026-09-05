package org.example.utils;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.io.File;
import java.util.Map;

public class DbHelper {
    public static SessionFactory buildSessionFactory(File configFile, Map<String, String> propertyOverrides) {
        if (configFile == null || !configFile.exists()) {
            throw new IllegalArgumentException("Hibernate configuration file not found: " + (configFile != null ? configFile.getAbsolutePath() : "null"));
        }
        Configuration cfg = new Configuration().configure(configFile);
        if (propertyOverrides != null) {
            for (Map.Entry<String, String> entry : propertyOverrides.entrySet()) {
                cfg.setProperty(entry.getKey(), entry.getValue());
            }
        }
        return cfg.buildSessionFactory();
    }
}