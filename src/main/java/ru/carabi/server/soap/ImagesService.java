package ru.carabi.server.soap;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.kernel.ImagesBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 *
 * @author aleksandr
 */
@WebService(serviceName = "ImagesService")
public class ImagesService {
	@EJB private UsersControllerBean uc;
	@EJB private ImagesBean imagesBean;

	/**
	 * This is a sample web service operation
	 */
	@WebMethod(operationName = "getThumbnail")
	public FileOnServer getThumbnail(
			@WebParam(name = "token") String token,
			@WebParam(name = "original") FileOnServer original,
			@WebParam(name = "width") int width,
			@WebParam(name = "height") int height,
			@WebParam(name = "useKernelBase") boolean useKernelBase
		) {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return imagesBean.getThumbnail(logon, Settings.getMasterServer(), original, width, height, useKernelBase);
		} catch (CarabiException ex) {
			Logger.getLogger(ImagesService.class.getName()).log(Level.SEVERE, null, ex);
		}
		return null;
	}
}
