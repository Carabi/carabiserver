///*
// * To change this license header, choose License Headers in Project Properties.
// * To change this template file, choose Tools | Templates
// * and open the template in the editor.
// */
//package ru.carabi.server.face;
//
//import java.util.Locale;
//import java.util.logging.Logger;
//import javax.faces.context.FacesContext;
//import javax.faces.event.AbortProcessingException;
//import javax.faces.event.ActionEvent;
//import javax.faces.event.ActionListener;
//import javax.servlet.http.HttpServletRequest;
//
///**
// *
// * @author sasha
// */
//public class LocaleSet implements ActionListener {
//
//	@Override
//	public void processAction(ActionEvent event) throws AbortProcessingException {
//		HttpServletRequest request = (HttpServletRequest)FacesContext.getCurrentInstance().getExternalContext().getRequest();
//		Logger.getLogger(LocaleSet.class.getName()).info(request.getLocale().toLanguageTag());//ru-RU, ru, 
//		FacesContext.getCurrentInstance().getViewRoot().setLocale(request.getLocale());
//	}
//	
//}
