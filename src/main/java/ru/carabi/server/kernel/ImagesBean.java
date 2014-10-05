package ru.carabi.server.kernel;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.ThumbnailMeta;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.stub.ImagesService;
import ru.carabi.stub.ImagesService_Service;

/**
 * Обработка и хранение изображений
 * @author aleksandr
 */
@Stateless
public class ImagesBean {
	private static final Logger logger = CarabiLogging.getLogger(ImagesBean.class);
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-chat")
	EntityManager emChat;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	EntityManager emKernel;
	
	/**
	 * Создание миниатюры.
	 * @param logon пользовательская сессия
	 * @param targetServer сервер с оригиналом
	 * @param original данные об оригинале
	 * @param width желаемая ширина
	 * @param height желаемая высота
	 * @param useKernelBase данные о файлах хранятся в ядровой, а не в локальной базе
	 * @return 
	 */
	public FileOnServer getThumbnail(UserLogon logon, CarabiAppServer targetServer, FileOnServer original, int width, int height, boolean useKernelBase) throws CarabiException {
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callGetThumbnailSoap(targetServer, logon, original, width, height, useKernelBase);
		}
		EntityManager em = useKernelBase ? emKernel : emChat;
		TypedQuery<ThumbnailMeta> findThumbnail = em.createNamedQuery("findThumbnail", ThumbnailMeta.class);
		findThumbnail.setParameter("original", original);
		findThumbnail.setParameter("width", width);
		findThumbnail.setParameter("height", height);
		List<ThumbnailMeta> thumbnails = findThumbnail.getResultList();
		ThumbnailMeta thumbnailMetadata;
		FileOnServer thumbnail;
		if (thumbnails.isEmpty()) {//Миниатюра данного размера ещё не создана
			logger.log(Level.INFO, "createThumbnail {0}x{1}", new Object[]{width, height});
			thumbnail = createThumbnail(original, width, height, useKernelBase);
		} else {//Берём данные из базы с проверкой, что миниатюра не потеряна
			thumbnailMetadata = thumbnails.get(0);
			thumbnail = thumbnailMetadata.getThumbnail();
			if (thumbnail == null) {
				em.remove(thumbnailMetadata);
				thumbnail = createThumbnail(original, width, height, useKernelBase);
			}
			File thumbnailFile = new File(thumbnail.getContentAddress());
			if (!thumbnailFile.exists()) {
				em.remove(thumbnail);
				em.remove(thumbnailMetadata);
				thumbnail = createThumbnail(original, width, height, useKernelBase);
			}
		}
		return thumbnail;
	}
	
	/**
	 * Создание миниатюры на текущем сервере.
	 * @param original
	 * @param width
	 * @param height
	 * @param useKernelBase
	 * @return 
	 */
	private FileOnServer createThumbnail(FileOnServer original, int width, int height, boolean useKernelBase) throws CarabiException {
		//Вычисление расположения и размера миниатюры
		String scale = "x";
		String thumbnailAddr = Settings.THUMBNAILS_LOCATION + "/" + original.getId() + "_" + original.getName();
		if (width > 0) {
			scale = "" + width + scale;
			thumbnailAddr += "_w-" + width;
		}
		if (height > 0) {
			scale = scale + height;
			thumbnailAddr += "_h-" + height;
		}
		thumbnailAddr += ".jpeg";
		//Запуск ImageMagick Convert 
		logger.log(Level.INFO, "convert {0} -scale {1} {2}", new Object[]{original.getContentAddress(), scale, thumbnailAddr});
		ProcessBuilder procBuilder = new ProcessBuilder("convert", original.getContentAddress(), "-scale", scale, thumbnailAddr); 
		try {
			Process process = procBuilder.start();
			InputStream errorStream = process.getErrorStream();
			BufferedReader errorStreamReader = new BufferedReader(new InputStreamReader(errorStream));
			String line;
			while((line = errorStreamReader.readLine()) != null) {
				logger.warning(line);
			}
			int exitVal = process.waitFor();
			if (exitVal > 0) {
				throw new CarabiException("Thumbnail creating failed");
			}
		} catch (IOException | InterruptedException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
		//Запись в базу данных о миниатюре
		File thumbnailFile = new File(thumbnailAddr);
		FileOnServer thumbnail = new FileOnServer();
		thumbnail.setName(original.getName());
		thumbnail.setMimeType("image/jpeg");
		thumbnail.setContentAddress(thumbnailAddr);
		thumbnail.setContentLength(thumbnailFile.length());
		EntityManager em = useKernelBase ? emKernel : emChat;
		Query deleteOldThumbnail = em.createQuery("delete from FileOnServer F where F.contentAddress = :addr");
		deleteOldThumbnail.setParameter("addr", thumbnailAddr);
		deleteOldThumbnail.executeUpdate();
		thumbnail = em.merge(thumbnail);
		ThumbnailMeta thumbnailMetadata = new ThumbnailMeta();
		thumbnailMetadata.setOriginal(original);
		thumbnailMetadata.setThumbnail(thumbnail);
		thumbnailMetadata.setHeight(height);
		thumbnailMetadata.setWidth(width);
		em.merge(thumbnailMetadata);
		em.flush();
		return thumbnail;
	}

	private FileOnServer callGetThumbnailSoap(CarabiAppServer targetServer, UserLogon logon, FileOnServer original, int width, int height, boolean useKernelBase) {
		try {
			ImagesService imagesService = getImageServicePort(targetServer);
			ru.carabi.stub.FileOnServer thumbnailStub = imagesService.getThumbnail(logon.getToken(), original.createStub(), width, height, useKernelBase);
			FileOnServer thumbnail = new FileOnServer();
			thumbnail.setAllFromStub(thumbnailStub);
			return thumbnail;
		} catch (MalformedURLException ex) {
			Logger.getLogger(ImagesBean.class.getName()).log(Level.SEVERE, null, ex);
		}
		return null;
	}

	private ImagesService imageServicePort;
	private ImagesService getImageServicePort(CarabiAppServer targetServer) throws MalformedURLException {
		if (imageServicePort != null) {
			return imageServicePort;
		}
		StringBuilder url = new StringBuilder("http://");
		url.append(targetServer.getComputer());
		url.append(":");
		url.append(targetServer.getGlassfishPort());
		url.append("/");
		url.append(targetServer.getContextroot());
		url.append("/ChatService?wsdl");
		ImagesService_Service imagesService = new ImagesService_Service(new URL(url.toString()));
		imageServicePort = imagesService.getImagesServicePort();
		return imageServicePort;
	}
}
