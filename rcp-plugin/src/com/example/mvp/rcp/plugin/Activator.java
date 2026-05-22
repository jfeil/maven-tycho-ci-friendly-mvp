package com.example.mvp.rcp.plugin;

import com.example.mvp.lib.Greeter;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    @Override
    public void start(BundleContext context) {
        System.out.println(Greeter.greet("RCP plugin"));
    }

    @Override
    public void stop(BundleContext context) {
    }
}
