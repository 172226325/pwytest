package com.pwy.app.test;

import com.pwy.apt.annotation.Factory;

@Factory(id = "Rectangle", type = IShape.class)
public class Rectangle implements IShape {

    @Override
    public void draw() {
        System.out.println("Draw a Rectangle");
    }
}
