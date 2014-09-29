package ru.carabi.server.kernel;

import ru.carabi.server.entities.FileOnServer;

/**
 * Интерфейс объекта, отвечающего за пользовательские файлы на сервере
 * @author sasha
 */
public interface FileStorage {

	/**
	 * Создание нового объекта {@link FileOnServe} и сохранение его в базе для генерации ключа.
	 * @param userFilename пользовательское имя файла
	 * @return объект FileOnServer со сгенерированным ID
	 */
	FileOnServer createFileMetadata(String userFilename);

	/**
	 * Пересохранение объекта FileOnServer в базе.
	 */
	FileOnServer updateFileMetadata(FileOnServer fileMetadata);
	
}
