package ru.carabi.server.rest;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
/**
 *
 * @author sasha
 */
public class RestException extends WebApplicationException {
	public RestException(String message, Response.Status status) {
		super(Response.status(status).entity(message).type("text/plain").build());
	}
}
